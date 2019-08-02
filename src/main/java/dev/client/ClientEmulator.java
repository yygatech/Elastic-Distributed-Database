package dev.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.io.IOException;

import dev.logic.ActionType;
import dev.logic.Controller;
import dev.logic.EventQueue;
import dev.logic.LoadBalancer;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.logic.Executor;
import dev.logic.Monitor;

public class ClientEmulator {

	@Option(name = "-c", usage = "enable controller")
	private boolean enableController;
	// receives other command line parameters than options
	@Argument
	private List<String> arguments = new ArrayList<String>();
	private boolean endOfSimulation = false;
	private long startTime;
	
	/**
	 * The tpcw configurations
	 */
	private TPCWProperties tpcw = null;
	
	/**
	 * The monitor to collect metrics
	 */
	private Monitor monitor;
	
	/**
	 * The controller that implements control logic
	 */
	private Controller controller;
	
	/**
	 * The executor that executes commands
	 */
	private Executor executor;
	
	/**
	 * The loadbalancer that is used to distribute load
	 */
	private LoadBalancer loadBalancer;
	OpenSystemTicketProducer producer;
	EventQueue eventQueue = null;

	private static transient final Logger LOG = LoggerFactory.getLogger(ClientEmulator.class);

	public ClientEmulator() throws IOException, InterruptedException {
		super();
		tpcw = new TPCWProperties("tpcw");
		eventQueue = new EventQueue();
	}

	private synchronized void setEndOfSimulation() {
		endOfSimulation = true;
		LOG.info("Trigger ClientEmulator.isEndOfSimulation()= " + this.isEndOfSimulation());

	}

	public synchronized boolean isEndOfSimulation() {
		return endOfSimulation;
	}

	public void start(String[] args) {
		CmdLineParser parser = new CmdLineParser(this);
		try {
			// parse the arguments.
			parser.parseArgument(args);
			// you can parse additional arguments if you want.
			// parser.parseArgument("more","args");
		} catch (CmdLineException e) {
			// if there's a problem in the command line,
			// you'll get this exception. this will report
			// an error message.
			System.err.println(e.getMessage());
			System.err.println("java ClientEmulator [-c -d]");
			// print the list of available options
			parser.printUsage(System.err);
			System.err.println();
			return;
		}

		if (enableController)
			LOG.info("-c flag is set");

		long warmup = tpcw.warmup;
		long mi = tpcw.mi;
		long warmdown = tpcw.warmdown;
		
		int maxNumSessions = 0;
		int workloads[] = tpcw.workloads;
		for (int i = 0; i < workloads.length; i++) {
			if (workloads[i] > maxNumSessions) {
				maxNumSessions = workloads[i];
			}
		}
		LOG.info("The maximum is : " + maxNumSessions);
		BlockingQueue<Integer> bQueue = new LinkedBlockingQueue<Integer>();

		// Each usersession is a user
		UserSession[] sessions = new UserSession[maxNumSessions];
		for (int i = 0; i < maxNumSessions; i++) {
			sessions[i] = new UserSession(i, this, bQueue);
			sessions[i].holdThread();
			sessions[i].start();
		}

		int currNumSessions = 0;
		int currWLInx = 0;
		int diffWL = 0;

		// producer is for semi-open and open models
		// it shares a bQueue with all the usersessions.
		if (tpcw.mixRate > 0) {
			producer = new OpenSystemTicketProducer(this, bQueue);
			producer.start();
		}

		this.monitor = new Monitor(this);
		this.monitor.init();
		Timer timer = null;
		if (enableController) {
			this.controller = new Controller(this);
			timer = new Timer();
			timer.schedule(this.controller, warmup, tpcw.interval);
			this.executor = new Executor(this);
			this.executor.start();
		}
		this.loadBalancer = new LoadBalancer(this);
		LOG.info("Client starts......");
		this.startTime = System.currentTimeMillis();
		long endTime = startTime + warmup + mi + warmdown;
		long currTime;
		while (true) {
			currTime = System.currentTimeMillis();
			if (currTime >= endTime) {
				// when it reaches endTime, it ends.
				break;
			}
			diffWL = workloads[currWLInx] - currNumSessions;
			LOG.info("Workload......" + workloads[currWLInx]);
			if (diffWL > 0) {
				for (int i = currNumSessions; i < (currNumSessions + diffWL); i++) {
					sessions[i].releaseThread();
					sessions[i].notifyThread();
				}
			} else if (diffWL < 0) {
				for (int i = (currNumSessions - 1); i >= workloads[currWLInx]; i--) {
					sessions[i].holdThread();
				}
			}
			try {
				LOG.info("Client emulator sleep......" + tpcw.interval);
				Thread.sleep(tpcw.interval);
			} catch (InterruptedException ie) {
				LOG.error("ERROR:InterruptedException" + ie.toString());
			}
			currNumSessions = workloads[currWLInx];
			currWLInx = ((currWLInx + 1) % workloads.length);
		}
		setEndOfSimulation();
		if (enableController) {
			timer.cancel();
			try {
				this.eventQueue.put(ActionType.NoOp);
				executor.join();
				LOG.info("Executor joins");
			} catch (java.lang.InterruptedException ie) {
				LOG.error("Executor/Destroyer has been interrupted.");
			}
		}
		for (int i = 0; i < maxNumSessions; i++) {
			sessions[i].releaseThread();
			sessions[i].notifyThread();
		}
		LOG.info("Client: Shutting down threads ...");
		for (int i = 0; i < maxNumSessions; i++) {
			try {
				LOG.info("UserSession " + i + " joins.");
				sessions[i].join();
			} catch (java.lang.InterruptedException ie) {
				LOG.error("ClientEmulator: Thread " + i + " has been interrupted.");
			}
		}
		if (tpcw.mixRate > 0) {
			try {
				producer.join();
				LOG.info("Producer joins");
			} catch (java.lang.InterruptedException ie) {
				LOG.error("Producer has been interrupted.");
			}
		}
		LOG.info("Done\n");
		Runtime.getRuntime().exit(0);
	}

	public Monitor getMonitor() {
		return monitor;
	}

	public void setMonitor(Monitor monitor) {
		this.monitor = monitor;
	}

	public Controller getController() {
		return controller;
	}

	public void setController(Controller controller) {
		this.controller = controller;
	}

	public TPCWProperties getTpcw() {
		return tpcw;
	}

	public void setTpcw(TPCWProperties tpcw) {
		this.tpcw = tpcw;
	}

	public long getStartTime() {
		return startTime;
	}

	public void setStartTime(long startTime) {
		this.startTime = startTime;
	}

	public LoadBalancer getLoadBalancer() {
		return loadBalancer;
	}

	public void setLoadBalancer(LoadBalancer loadBalancer) {
		this.loadBalancer = loadBalancer;
	}

	public EventQueue getEventQueue() {
		return eventQueue;
	}

	public void setEventQueue(EventQueue eventQueue) {
		this.eventQueue = eventQueue;
	}

	public static void main(String[] args) throws IOException, InterruptedException {
		ClientEmulator client = new ClientEmulator();
		client.start(args);
	}
}
