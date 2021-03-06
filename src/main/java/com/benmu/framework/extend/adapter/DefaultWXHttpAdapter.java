package com.benmu.framework.extend.adapter;

import android.content.Context;
import android.content.res.AssetManager;
import android.text.TextUtils;
import android.util.Log;

import com.benmu.framework.constant.Constant;
import com.benmu.framework.manager.ManagerFactory;
import com.benmu.framework.manager.impl.FileManager;
import com.benmu.framework.manager.impl.PersistentManager;
import com.benmu.framework.model.Md5MapperModel;
import com.benmu.framework.utils.Md5Util;
import com.benmu.framework.utils.SharePreferenceUtil;
import com.taobao.weex.adapter.IWXHttpAdapter;
import com.taobao.weex.common.WXRequest;
import com.taobao.weex.common.WXResponse;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Created by Carry on 2017/8/7. interceptor for js resource
 */

public class DefaultWXHttpAdapter implements IWXHttpAdapter {
    private ExecutorService mExecutorService;
    private Context mContext;
    private String[] mFileFilter = {".js", ".css", ".html"};
    private String mBaseJs;
    private static final String BASE_JS_NAME = "base.js";

    private void execute(Runnable runnable) {
        if (mExecutorService == null) {
            mExecutorService = Executors.newFixedThreadPool(3);
        }
        mExecutorService.execute(runnable);
    }


    public DefaultWXHttpAdapter(Context context) {
        this.mContext = context;
    }

    @Override
    public void sendRequest(final WXRequest request, final OnHttpListener listener) {
        if (listener != null) {
            listener.onHttpStart();
        }
        execute(new Runnable() {
            @Override
            public void run() {
                if (!(Constant.INTERCEPTOR_ACTIVE.equals(SharePreferenceUtil.getInterceptorActive
                        (mContext))) || !isInterceptor(request
                        .url)) {
                    fetchUrl(request, listener);
                } else {
                    doInterceptor(request, listener);
                }

            }
        });
    }

    private boolean isInterceptor(String url) {
        for (String filter : mFileFilter) {
            if (url.endsWith(filter)) {
                return true;
            }
        }

        return false;
    }

    private void doInterceptor(WXRequest request, OnHttpListener listener) {
        WXResponse response = new WXResponse();
        String url = request.url;

        String subPath = url.substring(url.lastIndexOf("pages"));
        File bundleDir = ManagerFactory.getManagerService(FileManager.class).getBundleDir(mContext);
        File path = new File(bundleDir, subPath);
        if (listener != null) {
            listener.onHttpStart();
        }
        if (path.exists()) {
            //比较md5
            String targetMd5 = findMd5(path.getAbsolutePath());
            String currentMd5 = Md5Util.getFileMD5(path);
            if (currentMd5 == null) {
                //纪录错误   md5映射中找不到该路径
                if (listener != null) {
                    response.statusCode = "-1";
                    response.errorCode = "-1";
                    response.errorMsg = "映射中找不到:" + path.getAbsolutePath();
                    listener.onHttpFinish(response);
                }
                return;
            }
            if (!targetMd5.equals(currentMd5)) {
                //纪录错误  得到的md5与映射中md5不一致
                if (listener != null) {
                    response.statusCode = "-1";
                    response.errorCode = "-1";
                    response.errorMsg = "文件不匹配" + path.getAbsolutePath();
                    listener.onHttpFinish(response);
                }
                return;
            }
            //文件正确  加载本地js
            byte[] bytes = ManagerFactory.getManagerService(FileManager.class).loadLocalFile(path
                    .getAbsolutePath());
            if (listener != null) {
                response.statusCode = 200 + "";
                response.originalData = appendBaseJs(bytes);
                listener.onHttpFinish(response);
            }
        } else {
            if (listener != null) {
                response.statusCode = "-1";
                response.errorCode = "-1";
                response.errorMsg = "文件不存在" + path.getAbsolutePath();
                listener.onHttpFinish(response);
            }
        }
    }


    private String findMd5(String path) {
        List<Md5MapperModel.Item> lists = ManagerFactory.getManagerService(PersistentManager
                .class).getFileMapper();
        if (lists == null) return "";
        for (Md5MapperModel.Item item : lists) {
            if (path.endsWith(item.getPage())) {
                return item.getMd5();
            }
        }
        return "";
    }

    private byte[] appendBaseJs(byte[] origin) {
        if (mBaseJs == null) {
            AssetManager assets = mContext.getAssets();
            InputStream is = null;
            byte[] bytes = null;
            try {
                is = assets.open(BASE_JS_NAME);
                if (is != null) {
                    bytes = new byte[is.available()];
                    is.read(bytes);
                }
                if (bytes != null) {
                    mBaseJs = new String(bytes);
                }

            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                if (is != null) {
                    try {
                        is.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append(mBaseJs);
        sb.append("\n");
        sb.append(new String(origin));
        return sb.toString().getBytes();
    }


    private void fetchUrl(final WXRequest request, final OnHttpListener listener) {
        WXResponse response = new WXResponse();
        Log.e("url", request.url);
        try {
            HttpURLConnection connection = openConnection(request, listener);
            Map<String, List<String>> headers = connection.getHeaderFields();
            int responseCode = connection.getResponseCode();
            if (listener != null) {
                listener.onHeadersReceived(responseCode, headers);
            }

            response.statusCode = String.valueOf(responseCode);
            if (responseCode >= 200 && responseCode <= 299) {
                InputStream rawStream = connection.getInputStream();
                response.originalData = appendBaseJs(readInputStreamAsBytes(rawStream, listener));
            } else {
                response.errorMsg = readInputStream(connection.getErrorStream(), listener);
            }
            if (listener != null) {
                listener.onHttpFinish(response);
            }
        } catch (IOException | IllegalArgumentException e) {
            e.printStackTrace();
            response.statusCode = "-1";
            response.errorCode = "-1";
            response.errorMsg = e.getMessage();
            if (listener != null) {
                listener.onHttpFinish(response);
            }
            if (e instanceof IOException) {
            }
        }
    }


    /**
     * Opens an {@link HttpURLConnection} with parameters.
     *
     * @return an open connection
     */
    private HttpURLConnection openConnection(WXRequest request, OnHttpListener listener) throws
            IOException {
        URL url = new URL(request.url);
        HttpURLConnection connection = createConnection(url);
        connection.setConnectTimeout(request.timeoutMs);
        connection.setReadTimeout(request.timeoutMs);
        connection.setUseCaches(false);
        connection.setDoInput(true);

        if (request.paramMap != null) {
            Set<String> keySets = request.paramMap.keySet();
            for (String key : keySets) {
                connection.addRequestProperty(key, request.paramMap.get(key));
            }
        }

        if ("POST".equals(request.method) || "PUT".equals(request.method) || "PATCH".equals
                (request.method)) {
            connection.setRequestMethod(request.method);
            if (request.body != null) {
                if (listener != null) {
                    listener.onHttpUploadProgress(0);
                }
                connection.setDoOutput(true);
                DataOutputStream out = new DataOutputStream(connection.getOutputStream());
                out.write(request.body.getBytes());
                out.close();
                if (listener != null) {
                    listener.onHttpUploadProgress(100);
                }
            }
        } else if (!TextUtils.isEmpty(request.method)) {
            connection.setRequestMethod(request.method);
        } else {
            connection.setRequestMethod("GET");
        }

        return connection;
    }

    private byte[] readInputStreamAsBytes(InputStream inputStream, OnHttpListener listener)
            throws IOException {
        if (inputStream == null) {
            return null;
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        int nRead;
        int readCount = 0;
        byte[] data = new byte[2048];

        while ((nRead = inputStream.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
            readCount += nRead;
            if (listener != null) {
                listener.onHttpResponseProgress(readCount);
            }
        }

        buffer.flush();

        return buffer.toByteArray();
    }

    private String readInputStream(InputStream inputStream, OnHttpListener listener) throws
            IOException {
        if (inputStream == null) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        BufferedReader localBufferedReader = new BufferedReader(new InputStreamReader(inputStream));
        char[] data = new char[2048];
        int len;
        while ((len = localBufferedReader.read(data)) != -1) {
            builder.append(data, 0, len);
            if (listener != null) {
                listener.onHttpResponseProgress(builder.length());
            }
        }
        localBufferedReader.close();
        return builder.toString();
    }

    /**
     * Create an {@link HttpURLConnection} for the specified {@code url}.
     */
    protected HttpURLConnection createConnection(URL url) throws IOException {
        return (HttpURLConnection) url.openConnection();
    }

    public interface IEventReporterDelegate {
        void preConnect(HttpURLConnection connection, String body);

        void postConnect();

        InputStream interpretResponseStream(InputStream inputStream);

        void httpExchangeFailed(IOException e);
    }

    private static class NOPEventReportDelegate implements com.taobao.weex.adapter
            .DefaultWXHttpAdapter
            .IEventReporterDelegate {
        @Override
        public void preConnect(HttpURLConnection connection, String body) {
            //do nothing
        }

        @Override
        public void postConnect() {
            //do nothing
        }

        @Override
        public InputStream interpretResponseStream(InputStream inputStream) {
            return inputStream;
        }

        @Override
        public void httpExchangeFailed(IOException e) {
            //do nothing
        }
    }
}
