package com.beifeng.transformer.util;

import java.net.MalformedURLException;
import java.net.URL;

/**
 * URL工具类
 * Created by 蒙卓明 on 2017/7/8.
 */
public class UrlUtil {

    /**
     * 判断指定的host是否是一个有效的外链host
     *
     * @param host
     * @return
     */
    public static boolean isValidateInboundHost(String host) {
        if ("www.beifeng.com".equals(host) || "www.ibeifeng.com".equals(host)) {
            return false;
        }
        return true;
    }

    /**
     * 根据指定URL获取host
     *
     * @param url
     * @return
     * @throws MalformedURLException
     */
    public static String getHost(String url) throws MalformedURLException {
        URL u = getUrl(url);
        return u.getHost();
    }

    /**
     * 根据字符串url创建URL对象
     *
     * @param url
     * @return
     * @throws MalformedURLException
     */
    public static URL getUrl(String url) throws MalformedURLException {
        url = url.trim();
        if (!(url.startsWith("http:") || url.startsWith("https:"))) {
            url = "http:" + url;
        }
        return new URL(url);
    }
}
