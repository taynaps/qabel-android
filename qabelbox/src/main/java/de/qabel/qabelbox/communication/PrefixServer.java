package de.qabel.qabelbox.communication;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import de.qabel.qabelbox.communication.callbacks.JsonRequestCallback;
import de.qabel.qabelbox.config.AppPreference;
import okhttp3.Request;
import okhttp3.RequestBody;

/**
 * Created by danny on 11.02.2016.
 * <p/>
 * class to handle prefix server network action
 */
public class PrefixServer extends BaseServer {

    private final static String TAG = "PrefixServer";

    public PrefixServer(Context context) {
        super(context);
    }

    /**
     * main function for server action
     *
     * @param url
     * @param callback
     * @param token
     */
    private void doServerAction(String url, JsonRequestCallback callback, String token) {

        Request.Builder builder = new Request.Builder()
                .post(RequestBody.create(JSON, "{}"))
                .url(url);
        addHeader(token, builder);
        Request request = builder.build();
        Log.d(TAG, "request " + request.toString());
        doRequest(request, callback);
    }

    public void getPrefix(Context context, JsonRequestCallback callback) {
        doServerAction(urls.getPrefix(), callback, new AppPreference(context).getToken());
    }

    /**
     * parse all know server response fields, if available
     *
     * @param json
     * @return
     */
    public static ServerResponse parseJson(JSONObject json) {

        ServerResponse response = new ServerResponse();
        response.prefix = getJsonString("prefix", json);
        response.detail = getJsonString("detail", json);

        return response;
    }

    public final static class ServerResponse {

        public String prefix;
        public String detail;
    }
}
