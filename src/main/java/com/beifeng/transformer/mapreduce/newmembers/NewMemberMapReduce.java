package com.beifeng.transformer.mapreduce.newmembers;

import com.beifeng.common.DateEnum;
import com.beifeng.common.EventLogConstants;
import com.beifeng.common.GlobalConstants;
import com.beifeng.common.KpiType;
import com.beifeng.transformer.mapreduce.TransformerOutputFormat;
import com.beifeng.transformer.mapreduce.activemembers.ActiveMemberMapReduce;
import com.beifeng.transformer.mapreduce.totalmembers.TotalMemberCalculate;
import com.beifeng.transformer.model.dimension.StatsCommonDimension;
import com.beifeng.transformer.model.dimension.StatsUserDimension;
import com.beifeng.transformer.model.dimension.basic.BrowserDimension;
import com.beifeng.transformer.model.dimension.basic.DateDimension;
import com.beifeng.transformer.model.dimension.basic.KpiDimension;
import com.beifeng.transformer.model.dimension.basic.PlatformDimension;
import com.beifeng.transformer.model.value.map.TimeOutputValue;
import com.beifeng.transformer.model.value.reduce.MapWritableValue;
import com.beifeng.transformer.util.MemberUtil;
import com.beifeng.utils.JdbcManager;
import com.beifeng.utils.TimeUtil;
import com.google.common.collect.Lists;
import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.filter.*;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.mapreduce.TableMapReduceUtil;
import org.apache.hadoop.hbase.mapreduce.TableMapper;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.MapWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.WritableUtils;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.util.Tool;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 统计新增会员的MapReduce
 * Created by Administrator on 2017/7/5.
 */
public class NewMemberMapReduce extends Configured implements Tool {

    //日志打印对象
    private static final Logger logger = Logger.getLogger(ActiveMemberMapReduce.class);
    //HBase配置信息
    private static final Configuration conf = HBaseConfiguration.create();

    public static class NewMemberMapper extends TableMapper<StatsUserDimension, TimeOutputValue> {

        //打印日志对象
        private static final Logger logger = Logger.getLogger(NewMemberMapper.class);
        //HBase表列簇的二进制数据
        private byte[] family = Bytes.toBytes(EventLogConstants.HBASE_COLUMN_FAMILY_NAME);
        //Map输出的键
        private StatsUserDimension mapOutputKey = new StatsUserDimension();
        //Map输出的值
        private TimeOutputValue mapOutputValue = new TimeOutputValue();
        //用户基本信息分析模块的KPI维度信息
        private KpiDimension newMemberKpi = new KpiDimension(KpiType.NEW_MEMBER.name);
        //浏览器基本信息分析模块的KPI维度信息
        private KpiDimension newMemberOfBrowserKpi = new KpiDimension(KpiType.BROWSER_NEW_MEMBER.name);
        //数据库连接
        private Connection conn = null;

        @Override
        protected void setup(Context context) throws IOException, InterruptedException {
            //进行初始化操作
            Configuration conf = context.getConfiguration();
            try {
                //进行初始化操作
                conn = JdbcManager.getConnection(conf, GlobalConstants.WAREHOUSE_OF_REPORT);
                //删除指定日期的数据
                MemberUtil.deleteMemberInfoByDate(conf.get(GlobalConstants.RUNNING_DATE_PARAMS), conn);
            } catch (SQLException e) {
                logger.error("获取数据库连接出现异常", e);
                throw new IOException("连接数据库失败", e);
            }
        }

        @Override
        protected void map(ImmutableBytesWritable key, Result value, Context context) throws IOException,
                InterruptedException {

            //从HBase表中读取memberID
            String memberId = Bytes.toString(value.getValue(family, Bytes.toBytes(EventLogConstants
                    .LOG_COLUMN_NAME_MEMBER_ID)));
            //判断memberId是否是第一次访问，即判断该memberId是否是新增会员
            try {
                if (!MemberUtil.isValidateMemberId(memberId) || !MemberUtil.isNewMemberId(memberId, conn)) {
                    logger.warn("member id不能为空，且必须是第一次访问该网站的会员id");
                    return;
                }
            } catch (SQLException e) {
                logger.error("查询" + memberId + "是否是新增会员时出现数据库异常", e);
                throw new IOException("查询" + memberId + "是否是新增会员时出现数据库异常", e);
            }

            //从HBase表中读取服务器时间
            String serverTime = Bytes.toString(value.getValue(family, Bytes.toBytes(EventLogConstants
                    .LOG_COLUMN_NAME_SERVER_TIME)));
            //从HBase表中读取平台信息
            String platform = Bytes.toString(value.getValue(family, Bytes.toBytes(EventLogConstants
                    .LOG_COLUMN_NAME_PLATFORM)));

            //过滤无效数据，会员ID、服务器时间和平台信息有任意一个为空或者服务器时间非数字，则视该条记录为无效记录
            if (StringUtils.isBlank(serverTime) || !StringUtils.isNumeric(serverTime.trim()) || StringUtils
                    .isBlank(platform)) {
                //上述变量只要有一个为空，直接返回
                logger.warn("服务器时间、平台信息不能为空，服务器时间必须是数字");
                return;
            }

            //将服务器时间字符串转化为时间戳
            long longOfTime = Long.valueOf(serverTime.trim());
            //设置Map输出值对象timeOutputValue的属性值，只需要id
            mapOutputValue.setId(memberId);
            //构建日期维度信息
            DateDimension dateDimension = DateDimension.buildDate(longOfTime, DateEnum.DAY);
            //构建平台维度信息
            List<PlatformDimension> platformDimensions = PlatformDimension.buildList(platform);

            //从HBase表中读取浏览器的名称以及版本号，这两个值可以为空
            String browserName = Bytes.toString(value.getValue(family, Bytes.toBytes(EventLogConstants
                    .LOG_COLUMN_NAME_BROWSER_NAME)));
            String browserVersion = Bytes.toString(value.getValue(family, Bytes.toBytes(EventLogConstants
                    .LOG_COLUMN_NAME_BROWSER_VERSION)));
            //构建浏览器维度信息
            List<BrowserDimension> browserDimensions = BrowserDimension.buildList(browserName,
                    browserVersion);

            //Map输出
            StatsCommonDimension statsCommonDimension = mapOutputKey.getStatsCommon();
            statsCommonDimension.setDate(dateDimension);
            for (PlatformDimension pf : platformDimensions) {
                //清空statsUserDimension中BrowserDimension的内容
                mapOutputKey.getBrowser().clean();
                statsCommonDimension.setKpi(newMemberKpi);
                statsCommonDimension.setPlatform(pf);
                context.write(mapOutputKey, mapOutputValue);
                for (BrowserDimension bf : browserDimensions) {
                    statsCommonDimension.setKpi(newMemberOfBrowserKpi);
                    //由于需要进行clean操作，故将该值复制后填充
                    mapOutputKey.setBrowser(WritableUtils.clone(bf, context.getConfiguration()));
                    context.write(mapOutputKey, mapOutputValue);
                }
            }
        }

        @Override
        protected void cleanup(Context context) throws IOException, InterruptedException {
            //关闭数据库连接
            if(conn != null){
                try {
                    conn.close();
                } catch (SQLException e) {
                    //nothing
                }
            }
        }
    }

    public static class NewMemberReducer extends Reducer<StatsUserDimension, TimeOutputValue,
            StatsUserDimension, MapWritableValue> {

        //
        private Set<String> unique = new HashSet<String>();
        //Reduce输出的值
        private MapWritableValue outputValue = new MapWritableValue();
        //
        private MapWritable map = new MapWritable();

        @Override
        protected void reduce(StatsUserDimension key, Iterable<TimeOutputValue> values, Context context)
                throws IOException, InterruptedException {

            //进行清空操作
            unique.clear();

            //将会员ID添加到unique集合中，以便统计会员ID的去重个数
            for (TimeOutputValue value : values) {
                unique.add(value.getId());
            }

            //输出memberId
            outputValue.setKpi(KpiType.INSERT_MEMBER_INFO);
            for (String id : unique) {
                map.put(new IntWritable(-1), new Text(id));
                outputValue.setValue(map);
                context.write(key, outputValue);
            }

            map.put(new IntWritable(-1), new IntWritable(unique.size()));
            outputValue.setValue(map);
            //设置KPI
            outputValue.setKpi(KpiType.valueOfName(key.getStatsCommon().getKpi().getKpiName()));

            //进行输出
            context.write(key, outputValue);
        }
    }

    public int run(String[] args) throws Exception {
        //获取HBase配置信息
        Configuration conf = this.getConf();
        //向conf添加关于输出到MySQL的配置信息
        conf.addResource("output-collector.xml");
        conf.addResource("query-mapping.xml");
        conf.addResource("transformer-env.xml");

        //处理参数
        processArgs(conf, args);

        //创建并设置Job
        Job job = Job.getInstance(conf, this.getClass().getSimpleName());
        //设置HBase输入Mapper的参数
        //本地运行
        TableMapReduceUtil.initTableMapperJob(initScan(job), NewMemberMapper.class, StatsUserDimension
                .class, TimeOutputValue.class, job, false);
        //设置Reduce相关参数
        job.setReducerClass(NewMemberReducer.class);
        job.setOutputKeyClass(StatsUserDimension.class);
        job.setOutputValueClass(MapWritableValue.class);

        //设置输出的相关参数
        job.setOutputFormatClass(TransformerOutputFormat.class);
        //运行MapReduce任务
        if(job.waitForCompletion(true)){
            //计算当天新增会员数MapReduce任务成功后，执行计算当天总会员数任务
            new TotalMemberCalculate().calculateTotalMembers(conf);
            return 0;
        }
        return 1;
    }

    /**
     * 初始化Scan集合
     *
     * @param job Scan集合所属的MapReduce Job
     * @return Scan集合
     */
    private List<Scan> initScan(Job job) {
        //获取HBase配置信息
        Configuration config = job.getConfiguration();
        //获取运行时间，格式为yyyy-MM-dd
        String date = config.get(GlobalConstants.RUNNING_DATE_PARAMS);
        //将日期字符串转换成时间戳格式
        //起始
        long startDate = TimeUtil.parseString2Long(date);
        long endDate = startDate + GlobalConstants.MILLISECONDS_OF_DAY;
        //时间戳 + ...
        Scan scan = new Scan();
        //设定HBase扫描的起始rowkey和结束rowkey
        scan.setStartRow(Bytes.toBytes("" + startDate));
        scan.setStopRow(Bytes.toBytes("" + endDate));

        //创建HBase的过滤器集合
        FilterList filterList = new FilterList();
        //添加过滤器

        //Map任务需要获取的列名
        String[] columns = {EventLogConstants.LOG_COLUMN_NAME_MEMBER_ID, EventLogConstants
                .LOG_COLUMN_NAME_SERVER_TIME, EventLogConstants.LOG_COLUMN_NAME_PLATFORM, EventLogConstants
                .LOG_COLUMN_NAME_BROWSER_NAME, EventLogConstants.LOG_COLUMN_NAME_BROWSER_VERSION};
        //添加过滤器
        filterList.addFilter(getColumnFilter(columns));

        //将过滤器添加到scan对象中
        scan.setFilter(filterList);
        //设置HBase表名
        scan.setAttribute(Scan.SCAN_ATTRIBUTES_TABLE_NAME, Bytes.toBytes
                (EventLogConstants.HBASE_TABLE_NAME));
        return Lists.newArrayList(scan);
    }

    /**
     * 获取过滤给定列名的过滤器
     *
     * @param columns 列名集合
     * @return
     */
    private Filter getColumnFilter(String[] columns) {
        int length = columns.length;
        byte[][] filter = new byte[length][];
        for (int i = 0; i < length; i++) {
            filter[i] = Bytes.toBytes(columns[i]);
        }
        //返回根据列名前缀匹配的过滤器
        return new MultipleColumnPrefixFilter(filter);
    }

    /**
     * 处理参数
     *
     * @param conf
     * @param args
     */
    private void processArgs(Configuration conf, String[] args) {
        //时间格式字符串
        String date = null;
        for (int i = 0; i < args.length; i++) {
            if ("-d".equals(args[i])) {
                if (i + 1 < args.length) {
                    //此时args[i]不是最后一个参数，从传入的参数中获取时间格式字符串
                    date = args[++i];
                    break;
                }
            }
        }

        //判断date是否为空，以及其是否为有效的时间格式字符串
        //要求date的格式为yyyy-MM-dd
        if (StringUtils.isBlank(date) || !TimeUtil.isValidateRunningDate
                (date)) {
            //date为空或者是无效的时间格式字符串，使用默认时间
            //默认时间是前一天
            date = TimeUtil.getYesterday();

        }
        conf.set(GlobalConstants.RUNNING_DATE_PARAMS, date);
    }
}
