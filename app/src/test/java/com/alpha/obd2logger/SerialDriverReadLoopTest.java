package com.alpha.obd2logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Coverage for the Bluetooth SPP response read loop.
 *
 * <p>This loop carries the request/response desync logic — prompt detection,
 * stale-byte draining, byte validation and the bounded single retry — and had
 * no tests at all, despite being the code most likely to misbehave against a
 * real adapter mid-drive. The parser layer next door has 38 tests.
 */
public class SerialDriverReadLoopTest {

    /**
     * A fake adapter that only produces bytes <em>after</em> a command is
     * written, the way a real half-duplex link does.
     *
     * <p>Handing the driver a stream that already holds the reply would be
     * unfaithful: the driver drains buffered bytes before writing, precisely so
     * a stale reply cannot answer a new request. A pre-loaded stream therefore
     * gets (correctly) discarded and every response comes back empty.
     */
    private static final class Link {
        final SerialDriver driver;
        final List<String> commands = new ArrayList<>();

        private final Deque<byte[]> scriptedReplies = new ArrayDeque<>();
        private byte[] buffer = new byte[0];
        private int pos;

        Link(int timeoutMs) {
            LoggerConfig config = new LoggerConfig();
            config.connectionTimeoutMs = timeoutMs;
            driver = new SerialDriver(config);

            InputStream in = new InputStream() {
                @Override public int available() { return buffer.length - pos; }
                @Override public int read() {
                    return pos < buffer.length ? buffer[pos++] & 0xFF : -1;
                }
            };
            OutputStream out = new OutputStream() {
                @Override public void write(int b) {
                    write(new byte[] { (byte) b }, 0, 1);
                }
                @Override public void write(byte[] b, int off, int len) {
                    commands.add(new String(b, off, len, StandardCharsets.US_ASCII));
                    deliverNextReply();
                }
            };
            driver.attachStreamsForTest(in, out);
        }

        /** Queue a reply served when the next command is written. */
        Link reply(String text) {
            return replyBytes(text.getBytes(StandardCharsets.US_ASCII));
        }

        Link replyBytes(byte[] bytes) {
            scriptedReplies.add(bytes);
            return this;
        }

        /** Leave unread bytes in the buffer before any command goes out. */
        Link withStaleBytes(String text) {
            buffer = text.getBytes(StandardCharsets.US_ASCII);
            pos = 0;
            return this;
        }

        private void deliverNextReply() {
            byte[] next = scriptedReplies.poll();
            buffer = next != null ? next : new byte[0];
            pos = 0;
        }

        String send(String command) {
            return driver.sendCommand(command);
        }
    }

    private static String compact(String response) {
        return response.replace(" ", "");
    }

    /** A well-formed response terminated by the ELM prompt. */
    @Test
    public void readsResponseTerminatedByPrompt() {
        Link link = new Link(500).reply("41 0C 1A F8\r\r>");

        String response = link.send("010C");

        assertTrue("payload must survive sanitizing: " + response,
                compact(response).contains("410C1AF8"));
        assertEquals("command must be CR-terminated on the wire",
                "010C\r", link.commands.get(0));
    }

    /**
     * The loop must stop at the first prompt. Reading past it would pull the
     * next ECU response into this one and permanently desync request/response
     * pairing — every later reading would belong to the previous command.
     */
    @Test
    public void stopsAtFirstPrompt() {
        Link link = new Link(500).reply("41 0C 1A F8\r>41 0D 50\r>");

        String response = link.send("010C");

        assertTrue(compact(response).contains("410C1AF8"));
        assertFalse("must not read past the prompt into the next response: " + response,
                compact(response).contains("410D50"));
    }

    /**
     * Bytes left over from a previously timed-out command must be discarded
     * before the new command is written. Returning them would answer a speed
     * request with an RPM reply — the worst failure mode here, because the
     * value is well-formed and plausible, so nothing downstream can catch it.
     */
    @Test
    public void drainsStaleBytesSoTheyCannotAnswerTheNextCommand() {
        Link link = new Link(500)
                .withStaleBytes("41 0C 1A F8\r>")   // leftover RPM reply
                .reply("41 0D 50\r>");              // genuine answer to 010D

        String response = link.send("010D");

        assertFalse("stale RPM reply must not answer a speed request: " + response,
                compact(response).contains("410C1AF8"));
        assertTrue("genuine reply must be returned: " + response,
                compact(response).contains("410D50"));
    }

    /**
     * A response with no prompt is a partial frame, not data. It gets exactly
     * one bounded retry — an unbounded resend storm on a half-duplex link would
     * be worse than the fault it is recovering from.
     */
    @Test
    public void missingPromptTriggersExactlyOneRetry() {
        // Neither attempt sees a prompt, so the retry cap is what stops it.
        Link link = new Link(100)
                .reply("41 0C 1A F8\r")
                .reply("41 0C 1A F8\r");

        link.send("010C");

        assertEquals("a promptless frame must be retried once, not repeatedly",
                2, link.commands.size());
    }

    /**
     * Binary noise must be dropped rather than parsed as vehicle data. Built as
     * raw bytes because US-ASCII encoding folds a high byte to '?', which is
     * printable and would not exercise the filter at all.
     */
    @Test
    public void discardsNonPrintableNoise() {
        byte[] noisy = new byte[] {
                '4', '1', 0x00, ' ', '0', 'C', (byte) 0xFF, ' ',
                '1', 'A', ' ', 'F', '8', 0x0D, '>'
        };
        Link link = new Link(500).replyBytes(noisy);

        String response = link.send("010C");

        assertFalse("NUL must not survive into the response", response.indexOf(0x00) >= 0);
        assertFalse("high binary byte must not survive", response.indexOf((char) 0xFF) >= 0);
        assertTrue("valid payload must still be recovered: " + response,
                compact(response).contains("410C1AF8"));
    }

    /**
     * A half-open socket reports data but reads EOF. The driver must drop the
     * connection so the reconnect state machine takes over, rather than
     * returning empty responses forever while the UI still says "connected".
     */
    @Test
    public void endOfStreamMarksDriverDisconnected() {
        LoggerConfig config = new LoggerConfig();
        config.connectionTimeoutMs = 200;
        SerialDriver driver = new SerialDriver(config);
        driver.attachStreamsForTest(new InputStream() {
            @Override public int available() { return 1; }  // claims data...
            @Override public int read() { return -1; }       // ...but is at EOF
        }, new ByteArrayOutputStream());

        driver.sendCommand("010C");

        assertFalse("EOF must drop the connection so reconnect can run",
                driver.isConnected());
    }

    /** A broken stream must bail out rather than spin until the deadline. */
    @Test
    public void ioErrorDuringReadMarksDriverDisconnected() {
        LoggerConfig config = new LoggerConfig();
        config.connectionTimeoutMs = 200;
        SerialDriver driver = new SerialDriver(config);
        driver.attachStreamsForTest(new InputStream() {
            @Override public int available() throws IOException {
                throw new IOException("socket closed");
            }
            @Override public int read() throws IOException {
                throw new IOException("socket closed");
            }
        }, new ByteArrayOutputStream());

        assertEquals("", driver.sendCommand("010C"));
        assertFalse(driver.isConnected());
    }

    /** An adapter fault surviving the retry means the session is broken. */
    @Test
    public void persistentAdapterFaultDisconnects() {
        Link link = new Link(200)
                .reply("BUFFER FULL\r>")
                .reply("BUFFER FULL\r>");

        link.send("010C");

        assertEquals("fault path must still retry exactly once", 2, link.commands.size());
        assertFalse("persistent BUFFER FULL is a broken adapter session",
                link.driver.isConnected());
    }

    /**
     * "NO DATA" is an ECU saying it has no value for a PID, not a dead link.
     * Treating it as a transport fault would retry unsupported PIDs at poll
     * frequency and stall the loop on every vehicle.
     */
    @Test
    public void noDataIsAnEcuAnswerNotATransportFault() {
        Link link = new Link(200).reply("NO DATA\r>");

        String response = link.send("0146");

        assertTrue("NO DATA must reach the caller: " + response, response.contains("NO DATA"));
        assertEquals("NO DATA must not be retried", 1, link.commands.size());
        assertTrue("NO DATA must not drop the connection", link.driver.isConnected());
    }
}
