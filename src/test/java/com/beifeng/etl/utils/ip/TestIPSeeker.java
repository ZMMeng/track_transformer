package com.beifeng.etl.utils.ip;

import org.junit.Test;

/**
 * Created by Administrator on 2017/6/30.
 */
public class TestIPSeeker {

    private IPSeeker ipSeeker = IPSeeker.getInstance();

    @Test
    public void testIPSeeker() {
        String ip;
        ip = "180.173.83.113";//上海市
        ip = "203.198.23.69"; //香港
        ip = "202.175.34.24"; //澳门
        ip = "61.50.219.42"; //北京市
        ip = "222.33.76.2"; //辽宁省大连市瓦房店市
        ip = "222.36.52.37"; //天津市
        ip = "110.194.73.18";//山东省德州市
        ip = "58.59.26.157"; //山东省济南市
        ip = "163.19.9.247"; //台湾省
        ip = "61.57.155.236"; //台湾省
        ip = "61.128.101.0";//新疆乌鲁木齐市
        ip = "219.159.235.101";//广西南宁市
        ip = "120.193.135.255";//内蒙古阿拉善盟
        ip = "120.193.141.0";//内蒙古锡林郭勒盟
        ip = "117.145.128.0";//新疆阿克苏地区
        ip = "218.7.241.101";//黑龙江省大兴安岭地区
        ip = "36.48.248.255";//吉林省延边州
        ip = "61.243.241.0";//吉林省四平市公主岭市
        ip = "139.209.254.0";//吉林省通化市梅河口市
        ip = "49.119.32.0"; //新疆克孜勒苏柯尔克孜州
        ip = "202.98.253.72"; //西藏阿里地区
        ip = "27.24.237.255";//湖北省恩施州宣恩县
        ip = "58.44.178.0";//湖南省湘西州
        ip = "1.206.110.255";//贵州省黔西南州兴义市
        ip = "61.188.255.255";//四川省阿坝州
        ip = "14.104.47.220";//重庆市
        ip = "125.72.159.177";//青海省玉树州
        ip = "3.255.255.255";
        System.out.println(ipSeeker.getCountry(ip));
    }
}
