package com.blackberry.kaboom;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import kafka.javaapi.PartitionMetadata;

import org.apache.curator.framework.CuratorFramework;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.blackberry.common.threads.NotifyingThread;
import com.blackberry.common.conversion.Converter;

public class ReadyFlagWriter extends NotifyingThread {

	private static final Logger LOG = LoggerFactory.getLogger(ReadyFlagWriter.class);
	private static final Charset UTF8 = Charset.forName("UTF-8");
	private CuratorFramework curator;
	private Map<String, String> topicFileLocation;
	private String kafkaZkConnectionString;
	private String kafkaSeedBrokers;
	private static final Object fsLock = new Object();
	private FileSystem fs;
	private Configuration hConf;
	private static final String ZK_ROOT = "/kaboom"; 	//TODO: This is currently a duplicate hard coded variable...  Also exists in Worker.java
	public static final String READY_FLAG = "_KAFKA_READY";
	public static final String FLAG_DIR = "/incoming";
	public static final String LOG_TAG = "[ready flag writer]";
	
	public ReadyFlagWriter(String kafkaZkConnectionString, 
			String kafkaSeedBrokers, 
			CuratorFramework curator, 
			Map<String, String> topicFileLocation,
			Configuration hConf) throws Exception {
		this.kafkaZkConnectionString = kafkaZkConnectionString;
		this.kafkaSeedBrokers = kafkaSeedBrokers;
		this.curator = curator;
		this.topicFileLocation = topicFileLocation;
		this.hConf = hConf;
	}
	
	/*
	 * Takes an HDFS (or template)
	 * Looks for the last occurrence of a directory 
	 * Truncates after the last occurrence of the directory 
	 */
	
	public static String flagRootFromHdfsPath(String hdfsPath)	 
	{		
		String flagRoot = new String();
		int lastCharPos = hdfsPath.lastIndexOf(FLAG_DIR);
		//Check to see if last occurrence of FLAG_DIR is the end of the string
		if (hdfsPath.length() == lastCharPos + FLAG_DIR.length()) {
			flagRoot = hdfsPath;
		}
		else {
			flagRoot = hdfsPath.substring(0, lastCharPos + FLAG_DIR.length());
		}		
		return flagRoot;
	}

	/*
	 * Do we need to write the _KAFKA_READY flag for the last hour? 
	 * 
	 * Look at all the partitions of a topic
	 * If all the topic's partitions's offsets have passed the previous hour 
	 * Then write the _KAFKA_READY flag in the previous hour's incoming directory
	 * 
	 * Note: This is intended to be invoked infrequently (no more than once every 
	 * 10 minutes or so... If that frequency increases then optimizations should 
	 * be explored.
	 * 
	 * Note: This isn't intelligent enough to look earlier than the previous hour.
	 * If this method isn't called at least once per hour then there will be flags 
	 * that will never bet set.
	 *      		
	 */	
	@Override
	public void doRun() throws Exception 
	{	
		Map<String, List<PartitionMetadata>> topicsWithPartitions = new HashMap<String, List<PartitionMetadata>>();
		StateUtils.getTopicParitionMetaData(kafkaZkConnectionString, kafkaSeedBrokers, topicsWithPartitions);
		
		Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
		
		long currentTimestamp = cal.getTimeInMillis();
		long startOfHourTimestamp = currentTimestamp - currentTimestamp % (60 * 60 * 1000);
		long prevHourStartTimestmap = startOfHourTimestamp - (60 * 60 * 1000);
		
		Calendar previousHourCal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
		previousHourCal.setTimeInMillis(prevHourStartTimestmap);		

		for (Map.Entry<String, List<PartitionMetadata>> entry : topicsWithPartitions.entrySet()) 
		{
			LOG.debug(LOG_TAG + " Checking {} partition(s) in topic={} for offset timestamps...", entry.getValue().size(), entry.getKey());
			String hdfsFlagTemplate = topicFileLocation.get(entry.getKey());			
			
			if (hdfsFlagTemplate == null) {
				LOG.error(LOG_TAG + " HDFS path property for topic={} is not defined in configuraiton, skipping topic", entry.getKey());
				continue;
			}
			
			LOG.debug(LOG_TAG + " original hdfsFlagTemplate={}", hdfsFlagTemplate);			
			hdfsFlagTemplate = flagRootFromHdfsPath(hdfsFlagTemplate);
			LOG.debug(LOG_TAG + " flagRootFromHdfsPath returns {}", hdfsFlagTemplate);
			hdfsFlagTemplate = hdfsFlagTemplate + "/" + READY_FLAG;
			Path hdfsFlagPath = new Path(Converter.timestampTemplateBuilder(prevHourStartTimestmap, hdfsFlagTemplate));
			LOG.debug(LOG_TAG + " HDFS path for the ready flag is: {}", hdfsFlagPath.toString());			
						
			synchronized (fsLock) {
				try {
					fs = hdfsFlagPath.getFileSystem(this.hConf);
				} catch (IOException e) {
					LOG.error(LOG_TAG + " Error getting File System for path {}, error: {}.", hdfsFlagPath.toString(), e);
				}
			}
			
			LOG.debug("[ready_flag_writer] Opening {}", hdfsFlagPath.toString());
			
			if(fs.exists(hdfsFlagPath)) {
				LOG.debug(LOG_TAG + " flag {} already exists at {}", READY_FLAG, hdfsFlagPath.toString());
				continue;
			}
			
			long oldestTimestamp = -1;
			
			for (PartitionMetadata partition : entry.getValue()) {
				String zk_offset_path = ZK_ROOT + "/topics/" + entry.getKey() + "/" + partition.partitionId() + "/offset_timestamp";				
				Stat stat = curator.checkExists().forPath(zk_offset_path);
				if (stat != null) {
					Long thisTimestamp = Converter.longFromBytes(curator.getData().forPath(zk_offset_path), 0);
					LOG.debug(LOG_TAG + " found topic={} partition={} offset timestamp={}", 
							entry.getKey(), partition.partitionId(), thisTimestamp);					
					if (thisTimestamp < oldestTimestamp || oldestTimestamp == -1) {
						oldestTimestamp = thisTimestamp;						
					}
					stat = null;					
				} else {
					LOG.error(LOG_TAG + " cannot get stat for path {}", zk_offset_path);
				}				
			}
			
			LOG.debug(LOG_TAG + " oldest timestamp for topic={} is {}", entry.getKey(), oldestTimestamp);			
			
			if (oldestTimestamp > startOfHourTimestamp) {
				LOG.debug(LOG_TAG + " oldest timestamp is within the current hour, flag write required");
				fs.create(hdfsFlagPath).close();
				LOG.debug(LOG_TAG + " flag {} written", hdfsFlagPath.toString());
			}			
		}		
	}
}
