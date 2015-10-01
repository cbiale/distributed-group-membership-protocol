package cs425.mp2;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static cs425.mp2.FailureDetector.getSpreadTime;

public class PingSender extends Thread{
    private static final int SUBGROUP_K=2;
	private final DatagramSocket socket;
    private final long pingTimeOut;
    private final long protocolTime;
    private final Set<String> memberSet;
    private final ConcurrentHashMap<Info,Integer> infoMap;
    private final String idString;
    private final String introID;
    private AtomicInteger time;
    private AtomicBoolean ackReceived;
    private volatile boolean leave=false;

    public PingSender(DatagramSocket socket, Set<String> memberSet, AtomicBoolean ackReceived,
                      ConcurrentHashMap<Info,Integer> infoMap, String idStr, String introID,
                      AtomicInteger time, long pingTimeOut, long protocolTime) {
        this.socket=socket;
        this.pingTimeOut=pingTimeOut;
        this.protocolTime=protocolTime;
        this.memberSet=memberSet;
        this.infoMap=infoMap;
        this.idString=idStr;
        this.introID=introID;
        this.time=time;
        this.ackReceived=ackReceived;
    }

    public void terminate() {
        leave=true;
    }

    private void sendPing(String destID,AtomicInteger counterKey) {
        byte [] sendData = Message.MessageBuilder
                .buildPingMessage(String.valueOf(counterKey.get()),idString)
                .addInfoFromList(infoMap.keySet())
                .getMessage()
                .toByteArray();
        Pid destination = Pid.getPid(destID);
        sendMessage(sendData,destination);
    }

    private void sendPingReq(String relayerID, String destID,AtomicInteger counter) {
        byte [] sendData = Message.MessageBuilder
                .buildPingReqMessage(String.valueOf(counter.get()), idString, destID)
                .getMessage()
                .toByteArray();
        Pid destination = Pid.getPid(relayerID);
        sendMessage(sendData, destination);
    }

	private void sendMessage(byte [] sendData, Pid destination){
        System.out.println("[SENDER] [INFO] [" + System.currentTimeMillis() + "] message sent  : " +
                new String(sendData, 0, sendData.length) + " : destination : " + destination.pidStr);
		 try{
			 DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length,
                     InetAddress.getByName(destination.hostname),destination.port);
			 socket.send(sendPacket);
		 }catch(IOException e){
			 e.printStackTrace();
		 }
	}

    ListIterator<String> getShuffledMembers() {
        // TODO handle empty memberSet
        List<String> shuffledMembers=new ArrayList<String>(memberSet);
        Collections.shuffle(shuffledMembers);
        ListIterator<String> iterator=shuffledMembers.listIterator();
        return iterator;
    }

    private void updateInfoBuffer() {
        for (Info i : this.infoMap.keySet()) {
            if (infoMap.get(i)<this.time.get())
                infoMap.remove(i);
        }
    }

	@Override
	public void run(){
        ListIterator<String> shuffledIterator=getShuffledMembers();

		while(!leave) {
            long startTime = System.currentTimeMillis();

            if (!shuffledIterator.hasNext()) {
                shuffledIterator = getShuffledMembers();
                continue;
            }

            String pingMemberID = shuffledIterator.next();

            // skip if shuffledList contains a member which is deleted from memberSet in between
            if (!memberSet.contains(pingMemberID))
                continue;

            time.getAndIncrement();
            ackReceived.set(false);
            updateInfoBuffer();
            sendPing(pingMemberID, time);

            try {
                synchronized (ackReceived) {
                    ackReceived.wait(pingTimeOut);
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            if (ackReceived.get()) {
                sleepThread(startTime);
            } else {
                //if message not in awklist
                //send ping_requests
                ackReceived.set(false);
                ListIterator<String> shuffledk = getShuffledMembers();
                for (int i = 0; i < SUBGROUP_K; i++) {
                    if (!shuffledk.hasNext())
                        break;

                    String nextMember = shuffledk.next();
                    if (!memberSet.contains(nextMember)) {
                        i--;
                        continue;
                    }

                    sendPingReq(nextMember, pingMemberID, time);
                }

                try {
                    synchronized (ackReceived) {
                        ackReceived.wait(startTime + protocolTime - System.currentTimeMillis());
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

                if (!ackReceived.get()) {
                    if (pingMemberID.equals(introID)) {
                        System.out.println("[SENDER] [INFO] [" + System.currentTimeMillis() + "] : introducer " +
                                "failure detected " + ": " + pingMemberID);
                    } else {
                        this.memberSet.remove(pingMemberID);
                        this.infoMap.putIfAbsent(new Info(Info.InfoType.FAILED, pingMemberID), (int) FailureDetector
                                .getSpreadTime(memberSet.size()) + this.time.intValue());
                        System.out.println("[SENDER] [MEM_REMOVE] [" + System.currentTimeMillis() + "] : failure detected " +
                                ": " + pingMemberID);
                    }
                } else {
                    sleepThread(startTime);
                }
            }
        }

        leaveSequence();
	}

    private void leaveSequence() {
        int timePeriods=Math.min(memberSet.size(), (int) FailureDetector.getSpreadTime(memberSet.size()));
        Iterator<String> shuffledIterator=getShuffledMembers();
        for (int i=0;i<timePeriods;i++) {
            long startTime=System.currentTimeMillis();
            time.getAndIncrement();
            byte [] sendData = Message.MessageBuilder
                    .buildPingMessage(String.valueOf(time.get()),idString)
                    .addLeaveInfo(idString)
                    .getMessage()
                    .toByteArray();
            sendMessage(sendData,Pid.getPid(shuffledIterator.next()));
            sleepThread(startTime);
        }
    }

    private void sleepThread(long startTime) {
        try {
            Thread.sleep(startTime+protocolTime-System.currentTimeMillis());
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}
