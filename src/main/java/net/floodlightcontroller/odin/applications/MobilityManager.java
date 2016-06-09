package net.floodlightcontroller.odin.applications;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.floodlightcontroller.odin.master.OdinApplication;

import net.floodlightcontroller.odin.master.NotificationCallback;
import net.floodlightcontroller.odin.master.NotificationCallbackContext;
import net.floodlightcontroller.odin.master.OdinClient;
import net.floodlightcontroller.odin.master.OdinEventSubscription;
import net.floodlightcontroller.odin.master.OdinMaster;
import net.floodlightcontroller.odin.master.OdinEventSubscription.Relation;
import net.floodlightcontroller.util.MACAddress;

public class MobilityManager extends OdinApplication {
	protected static Logger log = LoggerFactory.getLogger(MobilityManager.class);
	/* A table including each client and its mobility statistics */
	private ConcurrentMap<MACAddress, MobilityStats> clientMap = new ConcurrentHashMap<MACAddress, MobilityStats> ();
	private final long HYSTERESIS_THRESHOLD; // milliseconds
	private final long IDLE_CLIENT_THRESHOLD; // milliseconds
	private final long SIGNAL_STRENGTH_THRESHOLD; // dbm

	public MobilityManager () {
		this.HYSTERESIS_THRESHOLD = 30000;
		this.IDLE_CLIENT_THRESHOLD = 180000; // Must to be bigger than HYSTERESIS_THRESHOLD
		this.SIGNAL_STRENGTH_THRESHOLD = 0;
	}

	/**
	 * Register subscriptions
	 */
	private void init () {
		OdinEventSubscription oes = new OdinEventSubscription();
		/* FIXME: Add something in order to subscribe more than one STA */
		//oes.setSubscription("40:A5:EF:E5:93:DF", "signal", Relation.GREATER_THAN, 0); // One client
        oes.setSubscription("*", "signal", Relation.GREATER_THAN, 0); // All clients

		NotificationCallback cb = new NotificationCallback() {
			@Override
			public void exec(OdinEventSubscription oes, NotificationCallbackContext cntx) {
				handler(oes, cntx);
			}
		};
		/* Before executing this line, make sure the agents declared in poolfile are started */	
		registerSubscription(oes, cb);
	}

	@Override
	public void run() {
		/* When the application runs, you need some time to start the agents */
		this.giveTime(30000);
		this.channelAssignment();
		init (); 
	}

	private void handler (OdinEventSubscription oes, NotificationCallbackContext cntx) {
		OdinClient client = getClientFromHwAddress(cntx.clientHwAddress);
		int lastScanningResult;
		
		/* The client is not registered in Odin, exit */
		if (client == null)
			return;
		long currentTimestamp = System.currentTimeMillis();
		// Assign mobility stats object if not already done
		// add an entry in the clientMap table for this client MAC
		// put the statistics in the table: value of the parameter, timestamp, timestamp
		if (!clientMap.containsKey(cntx.clientHwAddress)) {
			clientMap.put(cntx.clientHwAddress, new MobilityStats(cntx.value, currentTimestamp, currentTimestamp, cntx.agent.getIpAddress(), 0));
		}
		// get the statistics of that client
		MobilityStats stats = clientMap.get(cntx.clientHwAddress);
				
		/* Scan and update statistics */
		for (InetAddress agentAddr: getAgents()) { // FIXME: scan for nearby agents only 
			if (!cntx.agent.getIpAddress().equals(agentAddr)) {
				lastScanningResult = scanClientFromAgent(agentAddr, cntx.clientHwAddress, getChannelFromAgent(agentAddr), 100);
				if (lastScanningResult >= stats.scanningResult) {
					updateStatsWithReassignment(stats, cntx.value, currentTimestamp, agentAddr, lastScanningResult);
				}
			}
		}
		
		/* Select the new agent */
		if (stats.signalStrength >= stats.scanningResult) {
			
		}
		
		/* Now, handoff */
		
		// The client is associated to Odin (it has an LVAP), but it does not have an associated agent
		// If client hasn't been assigned an agent, associate it to the current AP
		if (client.getLvap().getAgent() == null) {
			log.info("HandoverMultichannel: client hasn't been asigned an agent: handing off client " + cntx.clientHwAddress
					+ " to agent " + nextAgent + " at " + System.currentTimeMillis());
			handoffClientToAp(cntx.clientHwAddress, nextAgent);
			updateStatsWithReassignment (stats, cntx.value, currentTimestamp, stats.agentAddr, stats.scanningResult);
			return;
		}
		
		
		
		
		
	}
	
	/**
	 * This method will handoff a client in the event of its
	 * agent having failed.
	 *
	 * @param oes
	 * @param cntx
	 */
	private void handoffClient (OdinEventSubscription oes, NotificationCallbackContext cntx) {
		
		
		

		// Check for out-of-range client
		// a client has sent nothing during a certain time
		if ((currentTimestamp - stats.lastHeard) > IDLE_CLIENT_THRESHOLD) {
			log.info("HandoverMultichannel: client with MAC address " + cntx.clientHwAddress
					+ " was idle longer than " + IDLE_CLIENT_THRESHOLD/1000 + " sec -> Reassociating it to agent " + nextAgent);
			handoffClientToAp(cntx.clientHwAddress, nextAgent);
			updateStatsWithReassignment (stats, cntx.value, currentTimestamp);
			return;
		}

		// If this notification is from the agent that's hosting the client's LVAP update MobilityStats and handoff.
		// Else, update MobilityStats.
		if (client.getLvap().getAgent().getIpAddress().equals(cntx.agent.getIpAddress())) {
			stats.signalStrength = cntx.value;
			stats.lastHeard = currentTimestamp;			
			
			// Don't bother if we're not within hysteresis period
			if (currentTimestamp - stats.assignmentTimestamp < HYSTERESIS_THRESHOLD)
				return;
			// We're outside the hysteresis period, so compare signal strengths for a handoff
			// I check if the strength is higher (THRESHOLD) than the last measurement stored the
			// last time in the other AP
			if (cntx.value >= stats.signalStrength + SIGNAL_STRENGTH_THRESHOLD) {
				log.info("HandoverMultichannel: signal strengths: " + cntx.value + ">= " + stats.signalStrength + " + " + SIGNAL_STRENGTH_THRESHOLD + " :" + "handing off client " + cntx.clientHwAddress
						+ " to agent " + nextAgent);
				handoffClientToAp(cntx.clientHwAddress, nextAgent);
				updateStatsWithReassignment (stats, cntx.value, currentTimestamp);
				return;
			}
		}
		else {
			stats.signalStrength = cntx.value;
			stats.lastHeard = currentTimestamp;
		}
	}

	private void updateStatsWithReassignment (MobilityStats stats, long signalValue, long now, InetAddress agentAddr, int scanningResult) {
		stats.signalStrength = signalValue;
		stats.lastHeard = now;
		stats.assignmentTimestamp = now;
		stats.agentAddr = agentAddr;
		stats.scanningResult = scanningResult;
	}

	private class MobilityStats {
		public long signalStrength;
		public long lastHeard;			// timestamp where it was heard the last time
		public long assignmentTimestamp;	// timestamp it was assigned
		public InetAddress agentAddr;
		public int scanningResult;

		public MobilityStats (long signalStrength, long lastHeard, long assignmentTimestamp, InetAddress agentAddr, int scanningResult) {
			this.signalStrength = signalStrength;
			this.lastHeard = lastHeard;
			this.assignmentTimestamp = assignmentTimestamp;
			this.agentAddr = agentAddr;
			this.scanningResult = scanningResult;
		}
	}
	
	private void giveTime (int time) {
		try {
					Thread.sleep(time);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
	}

	/* FIXME: Do it in a suitable way */
	private void channelAssignment () {
		for (InetAddress agentAddr: getAgents()) {
			log.info("HandoverMultichannel: Agent IP: " + agentAddr.getHostAddress());
			if (agentAddr.getHostAddress().equals("192.168.1.7")){
				log.info ("HandoverMultichannel: Agent channel: " + getChannelFromAgent(agentAddr));
				setChannelToAgent(agentAddr, 4);
				log.info ("HandoverMultichannel: Agent channel: " + getChannelFromAgent(agentAddr));
			}
			if (agentAddr.getHostAddress().equals("192.168.1.8")){
				log.info ("HandoverMultichannel: Agent channel: " + getChannelFromAgent(agentAddr));
				setChannelToAgent(agentAddr, 10);
				log.info ("HandoverMultichannel: Agent channel: " + getChannelFromAgent(agentAddr));
			}
		}
	}
}