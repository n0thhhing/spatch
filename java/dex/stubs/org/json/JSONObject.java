package org.json;
// Compile-time stub only; Android supplies the JSON parser.
public class JSONObject {
    public JSONObject(String source) throws JSONException {}
    public JSONObject optJSONObject(String key){return null;}
    public String optString(String key,String fallback){return fallback;}
    public java.util.Iterator<String> keys(){return java.util.Collections.<String>emptyList().iterator();}
    public boolean has(String key){return false;}
    public Object get(String key) throws JSONException {return null;}
}
