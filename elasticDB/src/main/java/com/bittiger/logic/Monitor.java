package com.bittiger.logic;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Vector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bittiger.client.ClientEmulator;
import com.bittiger.client.Utilities;
import com.bittiger.querypool.CleanStatsQuery;
import com.bittiger.querypool.StatsQuery;

public class Monitor {

	public final Vector<Stats> read;
	public final Vector<Stats> write;
	private ClientEmulator c;
	private int seq = 0;
	private int rPos = 0;
	private int wPos = 0;

	private static transient final Logger LOG = LoggerFactory.getLogger(Monitor.class);

	public Monitor(ClientEmulator c) {
		read = new Vector<Stats>();
		write = new Vector<Stats>();
		this.c = c;
	}

	public void init() {
		Connection connection = null;
		Statement stmt = null;
		try {
			Class.forName("com.mysql.jdbc.Driver").newInstance();
			connection = DriverManager.getConnection(Utilities.getStatsUrl(c.getTpcw().writeQueue),
					c.getTpcw().username, c.getTpcw().password);
			connection.setAutoCommit(true);
			stmt = connection.createStatement();
			CleanStatsQuery clean = new CleanStatsQuery();
			stmt.executeUpdate(clean.getQueryStr());
			LOG.info("Clean stats at server " + c.getTpcw().writeQueue);
		} catch (Exception e) {
			LOG.error(e.toString());
		} finally {
			if (stmt != null)
				try {
					stmt.close();
				} catch (SQLException e) {
					e.printStackTrace();
				}
			if (connection != null)
				try {
					connection.close();
				} catch (SQLException e) {
					e.printStackTrace();
				}
		}
	}

	private void updateStats(double x, double u, double r, double w, double m) {
		Connection connection = null;
		Statement stmt = null;
		try {
			Class.forName("com.mysql.jdbc.Driver").newInstance();
			connection = DriverManager.getConnection(Utilities.getStatsUrl(c.getTpcw().writeQueue),
					c.getTpcw().username, c.getTpcw().password);
			connection.setAutoCommit(true);
			stmt = connection.createStatement();
			StatsQuery stats = new StatsQuery(x, u, r, w, m);
			stmt.executeUpdate(stats.getQueryStr());
			LOG.info("Stats: Interval:" + x + ", Queries:" + u + ", Read:" + r + ", Write:" + w + ", Nodes:" + m);
		} catch (Exception e) {
			LOG.error(e.toString());
		} finally {
			if (stmt != null)
				try {
					stmt.close();
				} catch (SQLException e) {
					e.printStackTrace();
				}
			if (connection != null)
				try {
					connection.close();
				} catch (SQLException e) {
					e.printStackTrace();
				}
		}
	}

	public void addQuery(int sessionId, String type, long start, long end) {
		// int id = Integer.parseInt(name.substring(name.indexOf("n") + 1));
		Stats stat = new Stats(sessionId, type, start, end);
		if (type.contains("b")) {
			read.add(stat);
		} else {
			write.add(stat);
		}
		LOG.debug(stat.toString());
	}

	public String readPerformance() {
		int rPrevPos = rPos;
		int wPrevPos = wPos;
		rPos = read.size();
		wPos = write.size();
		if (rPrevPos == 0 && wPrevPos == 0) {
			return null;
		}
		StringBuffer perf = new StringBuffer();
		long currTime = System.currentTimeMillis();
		long validStartTime = Math.max(c.getStartTime() + c.getTpcw().warmup, currTime - c.getTpcw().interval);
		long validEndTime = Math.min(c.getStartTime() + c.getTpcw().warmup + c.getTpcw().mi, currTime);
		long totalTime = 0;
		int count = 0;
		int totCount = 0;
		double avgRead = 0.0;
		double avgWrite = 0.0;
		for (int i = rPrevPos; i < rPos; i++) {
			Stats s = read.get(i);
			if ((validStartTime < s.start) && (s.start < validEndTime)) {
				count += 1;
				totalTime += s.duration;
			}
		}
		perf.append("R:" + count + ":" + totalTime);
		if (count > 0) {
			avgRead = totalTime / count;
			perf.append(":" + avgRead);
		} else {
			perf.append(":NA");
		}
		totCount += count;

		totalTime = 0;
		count = 0;
		for (int i = wPrevPos; i < wPos; i++) {
			Stats s = write.get(i);
			if ((validStartTime < s.start) && (s.start < validEndTime)) {
				count += 1;
				totalTime += s.duration;
			}
		}
		perf.append(",W:" + count + ":" + totalTime);
		if (count > 0) {
			avgWrite = totalTime / count;
			perf.append(":" + avgWrite);
		} else {
			perf.append(":NA");
		}
		totCount += count;
		updateStats(seq++, totCount, avgRead, avgWrite, c.getLoadBalancer().getReadQueue().size());
		return perf.toString();
	}

}
