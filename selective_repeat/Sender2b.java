import java.io.*;
import java.net.*;
import java.util.Arrays;
import java.util.Vector;

public class Sender2b {

    private static final String TAG = "[" + Sender2b.class.getSimpleName() + "]";
    private static final int DEBUG = 0;

    private DatagramSocket clientSocket;
    private String destServerName;
    private int destPort;
    private InetAddress destIPAddress;
    private String filePath;
    private int retryTimeout;
    private int windowSize;

    private int sequenceNum;                    // sequence number of packet to be sent
    private int retransmissionCounter;          // counter for number of packet retransmissions
    private int windowBase;                     // sequence number of last acknowledged packet (base of window)
    private Vector<byte[]> allPacketsList;      // list of all packets to be sent
    private Vector<Long> transmissionTimes;     // list of transmission times for all packets


    public Sender2b(String destServerName, int destPort, String filePath, int retryTimeout, int windowSize) {
        this.destServerName = destServerName;
        this.destPort = destPort;
        this.filePath = filePath;
        this.retryTimeout = retryTimeout;
        this.windowSize = windowSize;

        try {
            clientSocket = new DatagramSocket();
            clientSocket.setSoTimeout(1000);
        } catch (SocketException e) {
            e.printStackTrace();
        }
    }

    public void sendFile() {
        System.out.println(TAG + " Started sending file");

        long startTime = System.currentTimeMillis(); // start time for calculating throughput
        boolean canQuit =false;

        sequenceNum = 0;
        retransmissionCounter = 0;
        windowBase = -1;

        // get ip address of destination
        try {
            destIPAddress = InetAddress.getByName(destServerName);
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }

        // read file into a byte array
        File fileToSend = new File(filePath);
        byte[] fileBytes = getBytesFromFile(fileToSend);

        // prepare a list of all packets to be sent
        allPacketsList = prepareAllPackets(fileBytes);

        // initialize transmission times vector with all 0s
        transmissionTimes = new Vector<Long>(allPacketsList.size());
        for (int i=0; i<allPacketsList.size(); i++) {
        	transmissionTimes.add(0L);
        }

        // start timer handler thread which will take care of resending timed out packets
        Thread timerHandlerThread = new Thread(new TimerManagerRunnable());
        timerHandlerThread.start();

        while (sequenceNum < allPacketsList.size()) {
            byte[] packetBytes = allPacketsList.get(sequenceNum);
            DatagramPacket packetToSend = new DatagramPacket(packetBytes, packetBytes.length, destIPAddress, destPort);

            if (sequenceNum <= windowBase + windowSize) {   // if pipeline is not full
                try {
                    clientSocket.send(packetToSend);
                    transmissionTimes.add(System.currentTimeMillis());
                    if (DEBUG == 1) System.out.println(TAG + " Sent packet with sequence number: " + sequenceNum);

                    sequenceNum++;
                } catch (IOException e) {
                    e.printStackTrace();
                }
            } else {   // if pipeline is full
                while (true) {
                    byte[] ackBytes = new byte[2];
                    DatagramPacket ackPacket = new DatagramPacket(ackBytes, ackBytes.length);

                    try {
                        clientSocket.receive(ackPacket);
                        int ackSequenceNum = ((ackBytes[0] & 0xff) << 8) + (ackBytes[1] & 0xff);

                        if (DEBUG == 1) System.out.println(TAG + " Received acknowledgment with sequence number: " + ackSequenceNum);

                        synchronized (transmissionTimes) {
                            // mark received packet
                            transmissionTimes.set(ackSequenceNum, null);

                            // if ack sequence number is first in window, shift window to next unacknowledged sequence number
                            if (ackSequenceNum == windowBase + 1) {
                                for (int i = windowBase + 1; i < allPacketsList.size(); i++) {
                                    if (i < transmissionTimes.size()) {
                                        if (transmissionTimes.get(i) != null) {
                                            windowBase = i - 1;

                                            break;
                                        }
                                    }
                                }

                                break;
                            }
                        }
                    } catch (SocketTimeoutException e) {
                        canQuit = true;
                        System.out.println(TAG + " Sender shutting down since it hasn't received anything for quite some time.");
                        break;
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }

        // keep receiving acknowledgments until all acknowledgments received
        while (!canQuit) {
            byte[] ackBytes = new byte[2];
            DatagramPacket ackPacket = new DatagramPacket(ackBytes, ackBytes.length);

            try {
                clientSocket.receive(ackPacket);
                int ackSequenceNum = ((ackBytes[0] & 0xff) << 8) + (ackBytes[1] & 0xff);

                synchronized (transmissionTimes) {
                    // mark received packet
                    transmissionTimes.set(ackSequenceNum, null);

                    // if ack sequence number is first in window, shift window to next unacknowledged sequence number
                    if (ackSequenceNum == windowBase + 1) {
                        for (int i = windowBase + 1; i < allPacketsList.size(); i++) {
                            if (transmissionTimes.get(i) != null) {
                                windowBase = i - 1;
                                break;
                            }
                        }
                    }
                }

                // if all packets have been successfully transmitted (i.e all acknowledgments received), then quit
                if (getTransmittedPacketCount() == allPacketsList.size()) {
                    canQuit = true;
                    System.out.println(TAG + " Received final acknowledgment, now shutting down.");
                }
            } catch (SocketTimeoutException e) {
                canQuit = true;
                System.out.println(TAG + " Sender shutting down since it hasn't received anything for quite some time.");
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        clientSocket.close();

        // calculate and print throughput
        int fileSizeKB = fileBytes.length / 1024;
        double transferTime = (System.currentTimeMillis() - startTime) / 1000.0;
        double throughput = (double) fileSizeKB / transferTime;

        System.out.println("--------------------------------------");
        System.out.println("File size: " + fileSizeKB + " KB");
        System.out.println("Transfer time: " + transferTime + " seconds");
        System.out.println("Throughput: " + throughput + " KBps");
        System.out.println("\nNumber of re-transmissions: " + retransmissionCounter);
        System.out.println("--------------------------------------");
    }

    // this runnable keeps iterating through all packet transmission times, and re-sends any packet
    // whose timer has timed out, and then resets their timer as well
    private class TimerManagerRunnable implements Runnable {

        @Override
        public void run() {
            while (!clientSocket.isClosed()) {
                for (int i = windowBase + 1; i < windowBase + 1 + windowSize; i++) {
                    synchronized (transmissionTimes) {
                        if (i < allPacketsList.size()) {
                            if (transmissionTimes.get(i) != null) {
                                if ((System.currentTimeMillis() - transmissionTimes.get(i)) >= retryTimeout) {
                                    byte[] packetBytes = allPacketsList.get(i);
                                    DatagramPacket packetToSend = new DatagramPacket(packetBytes, packetBytes.length, destIPAddress, destPort);
                                    try {
                                        if (!clientSocket.isClosed()) {
                                            clientSocket.send(packetToSend);
                                            transmissionTimes.set(i, System.currentTimeMillis());
                                            retransmissionCounter++;
                                            if (DEBUG == 1) System.out.println(TAG + " Packet timer timed out, so resent packet with sequence number: " + i);
                                        }
                                    } catch (IOException e) {
                                        e.printStackTrace();
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private synchronized int getTransmittedPacketCount() {
        int counter = 0;
        for (Long transmissionTime : transmissionTimes) {
            if (transmissionTime == null) {
                counter++;
            }
        }
        return counter;
    }

    public static byte[] getBytesFromFile(File file) {
        byte[] buffer = new byte[4096];
        InputStream is = null;
        ByteArrayOutputStream baos = null;

        try {
            is = new FileInputStream(file);
            baos = new ByteArrayOutputStream((int) file.length());

            int read = 0;
            while ((read = is.read(buffer)) != -1) {
                baos.write(buffer, 0, read);
            }

            return baos.toByteArray();

        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            if (baos != null) {
                try {
                    baos.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        return null;
    }

    private static Vector<byte[]> prepareAllPackets(byte[] fileBytes) {
        Vector<byte[]> allPacketsList = new Vector<byte[]>();
        int sequenceNum = 0;

        for (int i = 0; i < fileBytes.length; i += 1021) {
            boolean isLastPacket = i + 1021 >= fileBytes.length;

            byte[] packetBytes = new byte[1024];

            // add 16 bit sequence number as first 2 bytes
            packetBytes[0] = (byte) (sequenceNum >> 8);
            packetBytes[1] = (byte) (sequenceNum);

            // add last message flag as 3rd byte
            packetBytes[2] = isLastPacket ? (byte) 1 : (byte) 0;

            // add file bytes to remaining 1021 bytes
            if (!isLastPacket) {
                for (int j = 0; j < 1021; j++) {
                    packetBytes[j + 3] = fileBytes[i + j];
                }
            } else {
                // if last packet, only write remaining bytes instead of 1021
                for (int j = 0; j < fileBytes.length - i; j++) {
                    packetBytes[j + 3] = fileBytes[i + j];
                }
            }

            allPacketsList.add(packetBytes);
            sequenceNum++;
        }

        return allPacketsList;
    }

    public static void main(String[] args) {
        Sender2b sender = new Sender2b(args[0], Integer.parseInt(args[1]), args[2], Integer.parseInt(args[3]) ,Integer.parseInt(args[4]));
        sender.sendFile();
    }
}