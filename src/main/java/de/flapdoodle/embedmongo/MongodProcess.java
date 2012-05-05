/**
 * Copyright (C) 2011
 *   Michael Mosmann <michael@mosmann.de>
 *   Martin Jöhren <m.joehren@googlemail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.flapdoodle.embedmongo;

import java.io.File;
import java.io.IOException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.flapdoodle.embedmongo.collections.Collections;
import de.flapdoodle.embedmongo.config.MongodConfig;
import de.flapdoodle.embedmongo.distribution.Distribution;
import de.flapdoodle.embedmongo.distribution.Platform;
import de.flapdoodle.embedmongo.io.ConsoleOutput;
import de.flapdoodle.embedmongo.io.LogWatch;
import de.flapdoodle.embedmongo.runtime.MongodShutdown;
import de.flapdoodle.embedmongo.runtime.NUMA;
import de.flapdoodle.embedmongo.runtime.Network;
import de.flapdoodle.embedmongo.runtime.ProcessControl;

public class MongodProcess {

	static final Logger _logger = Logger.getLogger(MongodProcess.class.getName());

	private final MongodConfig _config;
	private final MongodExecutable _mongodExecutable;
	private ProcessControl _process;
	private int _mongodProcessId;
	private ConsoleOutput _consoleOutput;

	private File _dbDir;

	boolean _processKilled = false;
	boolean _stopped = false;

	private Distribution _distribution;

	public MongodProcess(Distribution distribution, MongodConfig config, MongodExecutable mongodExecutable)
			throws IOException {
		_config = config;
		_mongodExecutable = mongodExecutable;
		_distribution = distribution;

		try {
			File dbDir;
			if (config.getDatabaseDir() != null) {
				dbDir = Files.createOrCheckDir(config.getDatabaseDir());
			} else {
				dbDir = Files.createTempDir("embedmongo-db");
				_dbDir = dbDir;
			}
			//			ProcessBuilder processBuilder = new ProcessBuilder(enhanceCommandLinePlattformSpecific(distribution,
			//					getCommandLine(_config, _mongodExecutable.getFile(), dbDir)));
			//			processBuilder.redirectErrorStream();
			//			_process = new ProcessControl(processBuilder.start());
			_process = ProcessControl.fromCommandLine(enhanceCommandLinePlattformSpecific(distribution,
					getCommandLine(_config, _mongodExecutable.getFile(), dbDir)));

			Runtime.getRuntime().addShutdownHook(new JobKiller());

			LogWatch logWatch = LogWatch.watch(_process.getReader(), "waiting for connections on port", "failed", 20000);
			if (logWatch.isInitWithSuccess()) {
				_mongodProcessId = getMongodProcessId(logWatch.getOutput(), -1);
				_consoleOutput = new ConsoleOutput(_process.getReader());
				_consoleOutput.setDaemon(true);
				_consoleOutput.start();
			} else {
				throw new IOException("Could not start mongod process");
			}

		} catch (IOException iox) {
			stop();
			throw iox;
		}
	}

	protected static int getMongodProcessId(String output, int defaultValue) {
		Pattern pattern = Pattern.compile("MongoDB starting : pid=([1234567890]+) port", Pattern.MULTILINE);
		Matcher matcher = pattern.matcher(output);
		if (matcher.find()) {
			String value = matcher.group(1);
			return Integer.valueOf(value);
		}
		return defaultValue;
	}

	private static List<String> getCommandLine(MongodConfig config, File mongodExecutable, File dbDir) {
		List<String> ret = new ArrayList<String>();
		ret.addAll(Arrays.asList(mongodExecutable.getAbsolutePath(), "-v", "--port", "" + config.getPort(), "--dbpath", ""
				+ dbDir.getAbsolutePath(), "--noprealloc", "--nohttpinterface", "--smallfiles"));
		if (config.isIpv6()) {
			ret.add("--ipv6");
		}
		return ret;
	}

	private List<String> enhanceCommandLinePlattformSpecific(Distribution distribution, List<String> commands) {
		if (NUMA.isNUMA(distribution.getPlatform())) {
			switch (distribution.getPlatform()) {
				case Linux:
					List<String> ret = new ArrayList<String>();
					ret.add("numactl");
					ret.add("--interleave=all");
					ret.addAll(commands);
					return ret;
				default:
					_logger.warning("NUMA Plattform detected, but not supported.");
			}
		}
		return commands;
	}

	public synchronized void stop() {
		if (!_stopped) {

			if (!sendKillToMongodProcess()) {
				sendStopToMongoInstance();
			}

			if (_process != null) {
				_process.stop();
			}
			waitForProcessGotKilled();

			if ((_dbDir != null) && (!Files.forceDelete(_dbDir)))
				_logger.warning("Could not delete temp db dir: " + _dbDir);

			if (_mongodExecutable.getFile() != null) {
				if (!Files.forceDelete(_mongodExecutable.getFile())) {
					_stopped = true;
					_logger.warning("Could not delete mongod executable NOW: " + _mongodExecutable.getFile());
				}
			}
		}
	}

	private boolean sendStopToMongoInstance() {
		try {
			return MongodShutdown.sendShutdown(Network.getLocalHost(), _config.getPort());
		} catch (UnknownHostException e) {
			_logger.log(Level.SEVERE, "sendStop", e);
		}
		return false;
		
//		if ((_distribution.getPlatform() == Platform.Windows) || (_distribution.getPlatform() == Platform.OS_X)) {
//			_logger.warning("\n" + "------------------------------------------------\n"
//					+ "On windows (and maybe osx) stopping mongod process could take too much time.\n"
//					+ "We will send shutdown to db for speedup.\n"
//					+ "This will cause some logging of exceptions which we can not suppress.\n"
//					+ "------------------------------------------------");
//			try {
//				Mongo mongo = new Mongo(new ServerAddress(Network.getLocalHost(), _config.getPort()));
//				DB db = mongo.getDB("admin");
//				//			db.doEval("db.shutdownServer();");
//				db.command(new BasicDBObject("shutdown", 1).append("force", true));
//			} catch (UnknownHostException e) {
//				_logger.log(Level.SEVERE, "sendStop", e);
//			} catch (MongoException e) {
//				//			_logger.log(Level.SEVERE, "sendStop", e);
//			}
//		}
	}

	private static boolean executeCommandLine(List<String> commandLine) {
		try {
			ProcessControl killProcess = ProcessControl.fromCommandLine(commandLine);
			Thread.sleep(100);
			killProcess.stop();
			return true;
		} catch (IOException e) {
			_logger.log(Level.SEVERE, "" + commandLine, e);
		} catch (InterruptedException e) {
			_logger.log(Level.SEVERE, "" + commandLine, e);
		}
		return false;
	}

	private boolean sendKillToMongodProcess() {
		if (_mongodProcessId != -1) {
			List<String> commandLine = Collections.newArrayList("kill", "-2", "" + _mongodProcessId);
			if (_distribution.getPlatform() == Platform.Windows) {
				commandLine = Collections.newArrayList("taskkill", "/pid", "" + _mongodProcessId);
			}
			return executeCommandLine(commandLine);
		}
		return false;
	}

	/**
	 * It may happen in tests, that the process is currently using some files in
	 * the temp directory, e.g. journal files (journal/j._0) and got killed at
	 * that time, so it takes a bit longer to kill the process. So we just wait
	 * for a second (in 10 ms steps) that the process got really killed.
	 */
	private void waitForProcessGotKilled() {
		final Timer timer = new Timer();
		timer.scheduleAtFixedRate(new TimerTask() {

			public void run() {
				try {
					_process.waitFor();
				} catch (InterruptedException e) {
					_logger.severe(e.getMessage());
				} finally {
					_processKilled = true;
					timer.cancel();
				}
			}
		}, 0, 10);
		// wait for max. 1 second that process got killed

		int countDown = 100;
		while (!_processKilled && (countDown-- > 0))
			try {
				Thread.sleep(10);
			} catch (InterruptedException e) {
				_logger.severe(e.getMessage());
			}
		if (!_processKilled) {
			timer.cancel();
			throw new IllegalStateException("Couldn't kill mongod process!");
		}
	}

	class JobKiller extends Thread {

		@Override
		public void run() {
			MongodProcess.this.stop();
		}
	}
}
