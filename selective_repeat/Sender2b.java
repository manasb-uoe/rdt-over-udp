import java.io.*;
import java.net.*;
import java.util.Arrays;
import java.util.Vector;

public class Sender2b {

    private final String TAG = "[" + Sender2b.class.getSimpleName() + "]";
    private DatagramSocket clientSocket;
    private static int IDEAL_RETRY_TIMEOUT = 3000;

    private String destServerName;
    private int destPort;
    private InetAddress destIPAddress;
    private String filePath;
    private int windowSize;

    private long startTime;                     // start time for calculating throughput
    private int sequenceNum;                    // sequence number of packet to be sent
    private int retransmissionCounter;          // counter for number of packet retransmissions
    private int windowBase;                     // sequence number of last acknowledged packet (base of window)
    private Vector<byte[]> allPacketsList;      // list of all packets to be sent
    private Vector<Long> transmissionTimes;     // list of transmission times for all packets
    private byte[] fileBytes;                   // file is read into this array


    public Sender2b(String destServerName, int destPort, String filePath, int windowSize) {
        this.destServerName = destServerName;
        this.destPort = destPort;
        this.filePath = filePath;
        this.windowSize = windowSize;

        try {
            clientSocket = new DatagramSocket();
            clientSocket.setSoTimeout(IDEAL_RETRY_TIMEOUT);
        } catch (SocketException e) {
            e.printStackTrace();
        }
    }

    public void sendFile() {
        System.out.println(TAG + " Started sending file");

        startTime = System.currentTimeMillis();
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
        fileBytes = getBytesFromFile(fileToSend);

        // prepare a list of all packets to be sent
        allPacketsList = prepareAllPackets(fileBytes);
        transmissionTimes = new Vector<Long>(allPacketsList.size());

        // start ack receiver thread
        Thread ackReceiverThread = new Thread(new AckReceiverRunnable());
        ackReceiverThread.start();

        // start timer handler thread
        Thread timerHandlerTrhead = new Thread(new TimerManagerRunnable());
        timerHandlerTrhead.start();

        while (sequenceNum < allPacketsList.size()) {
            byte[] packetBytes = allPacketsList.get(sequenceNum);
            DatagramPacket packetToSend = new DatagramPacket(packetBytes, packetBytes.length, destIPAddress, destPort);

            if (sequenceNum <= windowBase + windowSize) {   // if pipeline is not full
                try {
                    clientSocket.send(packetToSend);
                    transmissionTimes.add(System.currentTimeMillis());
                    System.out.println(TAG + " Sent packet with sequence number: " + sequenceNum);

                    sequenceNum++;
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private class AckReceiverRunnable implements Runnable {

        @Override
        public void run() {
            boolean isLastAckPacket = false;

            while (!isLastAckPacket) {
                byte[] ackBytes = new byte[2];

                DatagramPacket ackPacket = new DatagramPacket(ackBytes, ackBytes.length);

                try {
                    clientSocket.receive(ackPacket);
                    int ackSequenceNum = ((ackBytes[0] & 0xff) << 8) + (ackBytes[1] & 0xff);

                    // mark received packet
                    synchronized (transmissionTimes) {
                        transmissionTimes.set(ackSequenceNum, null);
                    }

                    System.out.println(TAG + " Received acknowledgment with sequence number: " + ackSequenceNum);

                    // if ack sequence number == window base + 1, shift window to next unacknowledged sequence number
                    if (ackSequenceNum == windowBase + 1) {
                        for (int i=windowBase+1; i<sequenceNum; i++) {
                            if (transmissionTimes.get(i) != null) {
                                windowBase = i-1;
                                System.out.println("--------windowbase shifted to: " + windowBase);
                                break;
                            }
                        }
                    }

                    // if ack sequence number == last packet's sequence number,
                    // set isLastAckPacket to true so that we can break from the while loop and close the socket
                    if (ackSequenceNum == allPacketsList.size() - 1) {
                        isLastAckPacket = true;
                        System.out.println(TAG + " Received final acknowledgment, now shutting down.");
                    }
                } catch (SocketTimeoutException e) {
                    System.out.println(TAG + " Socket timed out while waiting for acknowledgment - SocketTimeoutException");
                    System.out.println(Arrays.toString(transmissionTimes.toArray()));
                    clientSocket.close();
                    System.exit(1);
                }  catch (IOException e) {
                    e.printStackTrace();
                }
            }

            clientSocket.close();

            // calculate and print throughput
            int fileSizeKB = fileBytes.length / 1024;
            long transferTime = (System.currentTimeMillis() - startTime) / 1000;
            double throughput = (double) fileSizeKB / transferTime;

            System.out.println("--------------------------------------");
            System.out.println("File size: " + fileSizeKB + " KB");
            System.out.println("Transfer time: " + transferTime + " seconds");
            System.out.println("Throughput: " + throughput + " KBps");
            System.out.println("\nNumber of re-transmissions: " + retransmissionCounter);
            System.out.println("--------------------------------------");
        }
    }

    private class TimerManagerRunnable implements Runnable {

        @Override
        public void run() {
            while (!clientSocket.isClosed()) {
                for (int i = windowBase + 1; i < sequenceNum; i++) {
                    synchronized (transmissionTimes) {
                        if (transmissionTimes.get(i) != null) {
                            if (System.currentTimeMillis() - transmissionTimes.get(i) >= IDEAL_RETRY_TIMEOUT) {
                                byte[] packetBytes = allPacketsList.get(i);
                                DatagramPacket packetToSend = new DatagramPacket(packetBytes, packetBytes.length, destIPAddress, destPort);
                                try {
                                    clientSocket.send(packetToSend);
                                    transmissionTimes.set(i, System.currentTimeMillis());
                                } catch (IOException e) {
                                    e.printStackTrace();
                                }

                                retransmissionCounter++;
                                System.out.println(TAG + " Packet timer timed out, so resent packet with sequence number: " + sequenceNum);
                            }
                        }
                    }
                }
            }
        }
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
        Sender2b sender = new Sender2b(args[0], Integer.parseInt(args[1]), args[2], Integer.parseInt(args[3]));
        sender.sendFile();
    }
}
