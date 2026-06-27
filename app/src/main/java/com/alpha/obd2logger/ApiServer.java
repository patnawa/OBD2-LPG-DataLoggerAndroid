package com.alpha.obd2logger;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

import fi.iki.elonen.NanoHTTPD;

public class ApiServer extends NanoHTTPD {

    private volatile DataRecord latestData;
    private volatile boolean isLogging;

    public ApiServer(int port) {
        super(port);
    }

    public void setLatestData(DataRecord data, boolean isLogging) {
        this.latestData = data;
        this.isLogging = isLogging;
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        
        // Setup CORS headers for web clients
        Response response = null;
        if (Method.OPTIONS.equals(session.getMethod())) {
            response = newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "");
        } else if (Method.GET.equals(session.getMethod())) {
            if ("/api/status".equals(uri)) {
                response = handleStatus();
            } else if ("/api/data".equals(uri)) {
                response = handleData();
            } else if ("/api/map".equals(uri)) {
                response = handleMap();
            } else {
                response = newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found");
            }
        } else {
            response = newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "Method Not Allowed");
        }

        if (response != null) {
            response.addHeader("Access-Control-Allow-Origin", "*");
            response.addHeader("Access-Control-Allow-Headers", "origin, accept, content-type");
            response.addHeader("Access-Control-Allow-Methods", "GET, OPTIONS, HEAD");
        }
        
        return response;
    }

    private Response handleStatus() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("status", "ok");
            obj.put("logging", isLogging);
            if (latestData != null) {
                obj.put("fuelMode", latestData.getFuelMode());
                obj.put("vin", latestData.getVin());
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", obj.toString());
    }

    private Response handleData() {
        if (latestData == null) {
            return newFixedLengthResponse(Response.Status.OK, "application/json", "{}");
        }
        
        JSONObject obj = new JSONObject();
        try {
            obj.put("timestamp", latestData.getTimestamp());
            obj.put("elapsedS", latestData.getElapsedS());
            obj.put("fuelMode", latestData.getFuelMode());
            
            JSONObject sensors = new JSONObject();
            for (SensorSample s : latestData.getSamples()) {
                sensors.put(s.getName(), s.getValue());
            }
            obj.put("sensors", sensors);
            
        } catch (JSONException e) {
            e.printStackTrace();
        }
        
        return newFixedLengthResponse(Response.Status.OK, "application/json", obj.toString());
    }

    private Response handleMap() {
        return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"error\": \"Map endpoint not fully implemented\"}");
    }
}
