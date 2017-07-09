package com.beifeng.transformer.mapreduce.activeusers;

import com.beifeng.common.DateEnum;
import com.beifeng.common.EventLogConstants;
import com.beifeng.common.KpiType;
import com.beifeng.transformer.mapreduce.TransformerBaseMapReduce;
import com.beifeng.transformer.mapreduce.TransformerBaseMapper;
import com.beifeng.transformer.model.dimension.StatsCommonDimension;
import com.beifeng.transformer.model.dimension.StatsUserDimension;
import com.beifeng.transformer.model.dimension.basic.BrowserDimension;
import com.beifeng.transformer.model.dimension.basic.DateDimension;
import com.beifeng.transformer.model.dimension.basic.KpiDimension;
import com.beifeng.transformer.model.dimension.basic.PlatformDimension;
import com.beifeng.transformer.model.value.map.TimeOutputValue;
import com.beifeng.transformer.model.value.reduce.MapWritableValue;
import com.beifeng.utils.TimeUtil;
import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.filter.Filter;
import org.apache.hadoop.hbase.filter.FilterList;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.MapWritable;
import org.apache.hadoop.io.WritableUtils;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Partitioner;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.util.*;

/**
 * 自定义的统计活跃用户的MapReduce类
 * Created by Administrator on 2017/7/4.
 */
public class ActiveUserMapReduce extends TransformerBaseMapReduce {

    //日志打印对象
    private static final Logger logger = Logger.getLogger(ActiveUserMapReduce.class);
    //HBase配置信息
    private static final Configuration conf = HBaseConfiguration.create();

    public static class ActiveUserMapper extends TransformerBaseMapper<StatsUserDimension, TimeOutputValue> {

        //Map输出的键
        private StatsUserDimension mapOutputKey = new StatsUserDimension();
        //Map输出的值
        private TimeOutputValue mapOutputValue = new TimeOutputValue();
        //用户基本信息分析模块的KPI维度信息
        private KpiDimension activeUserKpi = new KpiDimension(KpiType.ACTIVE_USER.name);
        //浏览器基本信息分析模块的KPI维度信息
        private KpiDimension activeUserOfBrowserKpi = new KpiDimension(KpiType.BROWSER_ACTIVE_USER.name);
        //按小时统计活跃用户的KPI维度信息
        private KpiDimension hourlyActiveUserKpi = new KpiDimension(KpiType.HOURLY_ACTIVE_USER.name);
        //
        private String uuid, serverTime, platform, browserName, browserVersion;
        //
        private long longOfTime;

        @Override
        protected void map(ImmutableBytesWritable key, Result value, Context context) throws IOException,
                InterruptedException {

            //从HBase表中读取UUID
            uuid = getUuid(value);
            //从HBase表中读取服务器时间
            serverTime = getServerTime(value);
            //从HBase表中读取平台信息
            platform = getPlatform(value);

            //过滤无效数据，UUID、服务器时间和平台信息有任意一个为空或者服务器时间非数字，则视该条记录为无效记录
            if (StringUtils.isBlank(uuid) || StringUtils.isBlank(serverTime) || !StringUtils.isNumeric
                    (serverTime.trim()) || StringUtils.isBlank(platform)) {
                //上述变量只要有一个为空，直接返回
                logger.warn("UUID、服务器时间以及平台信息不能为空，服务器时间必须是数字");
                return;
            }

            //将服务器时间字符串转化为时间戳
            longOfTime = Long.valueOf(serverTime.trim());
            //设置Map输出值对象timeOutputValue的属性值
            //用于标识用户
            mapOutputValue.setId(uuid);
            //用于按小时分析
            mapOutputValue.setTimestamp(longOfTime);
            //构建日期维度信息
            DateDimension dateDimension = DateDimension.buildDate(longOfTime, DateEnum.DAY);
            //构建平台维度信息
            List<PlatformDimension> platformDimensions = PlatformDimension.buildList(platform);

            //从HBase表中读取浏览器的名称以及版本号，这两个值可以为空
            browserName = getBrowserName(value);
            browserVersion = getBrowserVersion(value);
            //构建浏览器维度信息
            List<BrowserDimension> browserDimensions = BrowserDimension.buildList(browserName,
                    browserVersion);


            //Map输出
            StatsCommonDimension statsCommonDimension = mapOutputKey.getStatsCommon();
            statsCommonDimension.setDate(dateDimension);
            for (PlatformDimension pf : platformDimensions) {
                //清空statsUserDimension中BrowserDimension的内容
                mapOutputKey.getBrowser().clean();
                statsCommonDimension.setKpi(activeUserKpi);
                statsCommonDimension.setPlatform(pf);
                context.write(mapOutputKey, mapOutputValue);

                statsCommonDimension.setKpi(hourlyActiveUserKpi);
                context.write(mapOutputKey, mapOutputValue);
                for (BrowserDimension bf : browserDimensions) {
                    statsCommonDimension.setKpi(activeUserOfBrowserKpi);
                    //由于需要进行clean操作，故将该值复制后填充
                    mapOutputKey.setBrowser(WritableUtils.clone(bf, context.getConfiguration()));
                    context.write(mapOutputKey, mapOutputValue);
                }
            }
        }
    }

    public static class ActiveUserReducer extends Reducer<StatsUserDimension, TimeOutputValue,
            StatsUserDimension, MapWritableValue> {

        //用于存储UUID，方便数据去重
        private Set<String> unique = new HashSet<String>();
        //用于存储UUID(按时段)，方便数据去重
        private Map<Integer, Set<String>> hourlyUnique = new HashMap<Integer, Set<String>>();
        //Reduce输出的值
        private MapWritableValue outputValue = new MapWritableValue();
        //outputValue中的value属性值(KPI为统计活跃用户)
        private MapWritable map = new MapWritable();
        //outputValue中的value属性值
        private MapWritable hourlyMap = new MapWritable();

        @Override
        protected void setup(Context context) throws IOException, InterruptedException {
            super.setup(context);
            //初识化hourlyMap和hourlyUnique，创建24个小时，以及存储的内容
            for (int i = 0; i < 24; i++) {
                hourlyMap.put(new IntWritable(i), new IntWritable(0));
                hourlyUnique.put(i, new HashSet<String>());
            }
        }

        @Override
        protected void reduce(StatsUserDimension key, Iterable<TimeOutputValue> values, Context context)
                throws IOException, InterruptedException {

            try {
                //获取key的KPI名称
                String kpiName = key.getStatsCommon().getKpi().getKpiName();
                //判断KPI类型
                if (KpiType.HOURLY_ACTIVE_USER.name.equals(kpiName)) {
                    //按小时统计活跃用户数KPI
                    for (TimeOutputValue value : values) {
                        //计算访问的小时
                        int hour = TimeUtil.getDateInfo(value.getTimestamp(), DateEnum.HOUR);
                        //将用户加入到对应时间段中
                        hourlyUnique.get(hour).add(value.getId());
                    }

                    for (Map.Entry<Integer, Set<String>> entry : hourlyUnique.entrySet()) {
                        hourlyMap.put(new IntWritable(entry.getKey()), new IntWritable(entry.getValue()
                                .size()));
                    }

                    //设置KPI
                    outputValue.setKpi(KpiType.HOURLY_ACTIVE_USER);
                    //设置value
                    outputValue.setValue(hourlyMap);
                    //输出
                    context.write(key, outputValue);
                } else {
                    //其他KPI

                    //将UUID添加到unique集合中，以便统计UUID的去重个数
                    for (TimeOutputValue value : values) {
                        unique.add(value.getId());
                    }


                    map.put(new IntWritable(-1), new IntWritable(unique.size()));
                    outputValue.setValue(map);
                    outputValue.setKpi(KpiType.valueOfName(key.getStatsCommon().getKpi().getKpiName()));

                    //进行输出
                    context.write(key, outputValue);
                }
            } finally {
                //清空
                unique.clear();
                map.clear();
                hourlyMap.clear();
                hourlyUnique.clear();
                for (int i = 0; i < 24; i++) {
                    hourlyMap.put(new IntWritable(i), new IntWritable(0));
                    hourlyUnique.put(i, new HashSet<String>());
                }
            }
        }
    }

    /**
     * 执行MapReduce Job之前运行的方法
     *
     * @param job
     * @throws IOException
     */
    @Override
    protected void beforeRunJob(Job job) throws IOException {
        super.beforeRunJob(job);
        // 每个统计维度一个reducer
        job.setNumReduceTasks(3);
        //设置分区类
        job.setPartitionerClass(ActiveUserPartitioner.class);
        //设置不进行推测执行
        job.setMapSpeculativeExecution(false);
        job.setReduceSpeculativeExecution(false);
    }

    /**
     * 获取HBase的过滤器
     *
     * @return
     */
    @Override
    protected Filter fetchHbaseFilter() {
        FilterList filterList = new FilterList();
        // 定义mapper中需要获取的列名
        String[] columns = new String[]{EventLogConstants.LOG_COLUMN_NAME_UUID, // UUID
                EventLogConstants.LOG_COLUMN_NAME_SERVER_TIME, // 服务器时间
                EventLogConstants.LOG_COLUMN_NAME_PLATFORM, // 平台名称
                EventLogConstants.LOG_COLUMN_NAME_BROWSER_NAME, // 浏览器名称
                EventLogConstants.LOG_COLUMN_NAME_BROWSER_VERSION // 浏览器版本号
        };
        filterList.addFilter(this.getColumnFilter(columns));
        return filterList;
    }

    /**
     * 自定义分区类
     */
    public static class ActiveUserPartitioner extends Partitioner<StatsUserDimension, TimeOutputValue> {

        public int getPartition(StatsUserDimension key, TimeOutputValue value, int
                numPartitions) {
            KpiType kpi = KpiType.valueOfName(key.getStatsCommon().getKpi().getKpiName());
            switch (kpi) {
                case ACTIVE_USER:
                    return 0;
                case BROWSER_ACTIVE_USER:
                    return 1;
                case HOURLY_ACTIVE_USER:
                    return 2;
                default:
                    throw new IllegalArgumentException("无法获取分区id，当前kpi:" + key.getStatsCommon().getKpi()
                            .getKpiName() + "，当前reducer个数:" + numPartitions);
            }
        }
    }
}
