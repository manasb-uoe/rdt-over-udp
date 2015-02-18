package com.manas.comn;

import java.io.*;
import java.net.*;
import java.util.ArrayList;

public class GoBackNSender {

    private final String TAG = "[" + GoBackNSender.class.getSimpleName() + "]";
    private DatagramSocket clientSocket;
    private static int IDEAL_RETRY_TIMEOUT = 50;

    public GoBackNSender() {
        try {
            clientSocket = new DatagramSocket();
            clientSocket.setSoTimeout(IDEAL_RETRY_TIMEOUT);
        }
        catch (SocketException e) {
            e.printStackTrace();
        }
    }

    public void sendFile(String destServerName, int destPort, String filePath, int windowSize) {
        System.out.println(TAG + " Started sending file");

        // get ip address of destination
        InetAddress destIPAddress = null;
        try {
            destIPAddress = InetAddress.getByName(destServerName);
        }
        catch (UnknownHostException e) {
            e.printStackTrace();
        }

        // read file into a byte array
        File fileToSend = new File(filePath);
        byte[] fileBytes = getBytesFromFile(fileToSend);

        long startTime = System.currentTimeMillis();    // start time for calculating throughput
        int sequenceNum = 0;                            // sequence number of packet to be sent
        boolean isLastPacket = false;                   // flag to indicate last packet
        int retransmissionCounter = 0;                  // counter for retransmissions
        int windowBase = 0;                             // sequence number of last acknowledged packet (base of window)
        int bytesSent = 0;                              // number of bytes sent so far

        // list of all packets sent so far
        ArrayList<byte[]> sentPacketsList = new ArrayList<byte[]>();

        while (bytesSent < fileBytes.length) {
            // prepare packet to be sent
            isLastPacket = bytesSent + 1021 >= fileBytes.length;
            byte[] packetBytes = preparePacketBytes(sequenceNum, isLastPacket, fileBytes, bytesSent);
            DatagramPacket packetToSend = new DatagramPacket(packetBytes, packetBytes.length, destIPAddress, destPort);

            if (sequenceNum < windowBase + windowSize) {   // if pipeline is not full
                // send packet and append it to sent packets list
                try {
                    clientSocket.send(packetToSend);
                    sentPacketsList.add(packetBytes);

                    System.out.println(TAG + " Sent packet with sequence number: " + sequenceNum);

                    sequenceNum++;
                    bytesSent += 1021;

                    // TODO this has only be added temporarily
                    Thread.sleep(5);
                }
                catch (IOException e) {
                    e.printStackTrace();
                }
                catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            else {   // if pipeline is full;
                while (true) {
                    boolean ackReceived = false;
                    byte[] ackBytes = new byte[2];

                    DatagramPacket ackPacket = new DatagramPacket(ackBytes, ackBytes.length);
                    int ackSequenceNum = 0;

                    try {
                        clientSocket.receive(ackPacket);
                        ackSequenceNum = ((ackBytes[0] & 0xff) << 8) + (ackBytes[1] & 0xff);
                        ackReceived = true;
                    }
                    catch (SocketTimeoutException e) {
                        System.out.println(TAG + " Socket timed out while waiting for acknowledgment");
                        ackReceived = false;
                    }
                    catch (SocketException e) {
                        e.printStackTrace();
                    }
                    catch (IOException e) {
                        e.printStackTrace();
                    }

                    // whenever ack is received, break to send next packet
                    // else, resend all unacknowledged packets in the current window
                    if (ackReceived) {
                        System.out.println(TAG + " Received Acknowledgment with sequence number: " + ackSequenceNum);

                        // if ack sequence number > window base, shift window forward
                        if (ackSequenceNum > windowBase) {
                            windowBase = ackSequenceNum;
                        }
                        break;
                    }
                    else {
                        for (int j = windowBase; j < sequenceNum; j++) {
                            byte[] packetToResendBytes = sentPacketsList.get(j);
                            DatagramPacket packetToResend = new DatagramPacket(packetToResendBytes, packetToResendBytes.length, destIPAddress, destPort);

                            try {
                                clientSocket.send(packetToResend);
                                retransmissionCounter += 1;

                                // TODO this has only be added temporarily
                                Thread.sleep(50);

                                System.out.println(TAG + " Resending packet with sequence number: " + j);
                            }
                            catch (IOException e) {
                                e.printStackTrace();
                            }
                            catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                        }
                    }
                }
            }
        }

        // continue to receive acknowledgements until last acknowledgement is received
        boolean isLastAckPacket = false;

        while (!isLastAckPacket) {
            while (true) {
                boolean ackReceived = false;
                byte[] ackBytes = new byte[2];

                DatagramPacket ackPacket = new DatagramPacket(ackBytes, ackBytes.length);
                int ackSequenceNum = 0;

                try {
                    clientSocket.receive(ackPacket);
                    ackSequenceNum = ((ackBytes[0] & 0xff) << 8) + (ackBytes[1] & 0xff);
                    ackReceived = true;
                }
                catch (SocketTimeoutException e) {
                    System.out.println(TAG + " Socket timed out while waiting for acknowledgment");
                    ackReceived = false;
                }
                catch (SocketException e) {
                    e.printStackTrace();
                }
                catch (IOException e) {
                    e.printStackTrace();
                }

                // whenever ack is received, break to receive other acknowledgments
                // else, resend all unacknowledged packets in the current window
                if (ackReceived) {
                    System.out.println(TAG + " Received acknowledgment with sequence number: " + ackSequenceNum);

                    // if ack sequence number > window base, shift window forward
                    if (ackSequenceNum > windowBase) {
                        windowBase = ackSequenceNum;
                    }

                    // if ack sequence number == last packet's sequence number,
                    // set isLastAckPacket to true so that we can break from both while loops and close the socket
                    if (ackSequenceNum == sequenceNum-1) {  // 1 subtracted since we incremented sequence number after sending last packet
                        isLastAckPacket = true;
                        System.out.println(TAG + " Received final acknowledgment, now shutting down.");
                    }

                    break;
                }
                else {
                    for (int j = windowBase; j < sequenceNum; j++) {
                        byte[] packetToResendBytes = sentPacketsList.get(j);
                        DatagramPacket packetToResend = new DatagramPacket(packetToResendBytes, packetToResendBytes.length, destIPAddress, destPort);

                        try {
                            clientSocket.send(packetToResend);
                            retransmissionCounter += 1;

                            // TODO this has only be added temporarily
                            Thread.sleep(50);

                            System.out.println(TAG + " Resending packet with sequence number: " + j);
                        }
                        catch (IOException e) {
                            e.printStackTrace();
                        }
                        catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                }
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

        }
        catch (FileNotFoundException e) {
            e.printStackTrace();
        }
        catch (IOException e) {
            e.printStackTrace();
        }
        finally {
            if (is != null) {
                try {
                    is.close();
                }
                catch (IOException e) {
                    e.printStackTrace();
                }
            }
            if (baos != null) {
                try {
                    baos.close();
                }
                catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        return null;
    }

    public static byte[] preparePacketBytes(int sequenceNum, boolean isLastPacket, byte[] fileBytes, int bytesSent) {
        byte[] packetBytes = new byte[1024];

        // add 16 bit sequence number as first 2 bytes
        packetBytes[0] = (byte) (sequenceNum >> 8);
        packetBytes[1] = (byte) (sequenceNum);

        // add last message flag as 3rd byte
        packetBytes[2] = isLastPacket ? (byte) 1 : (byte) 0;

        // add file bytes to remaining 1021 bytes
        if (!isLastPacket) {
            for (int j=0; j<1021; j++) {
                packetBytes[j+3] = fileBytes[bytesSent+j];
            }
        }
        else {
            // if last packet, only write remaining bytes instead of 1021
            for (int j=0; j<fileBytes.length-bytesSent; j++) {
                packetBytes[j+3] = fileBytes[bytesSent+j];
            }
        }

        return packetBytes;
    }

    public static void main(String[] args) {
        GoBackNSender sender = new GoBackNSender();
        sender.sendFile(args[0], Integer.parseInt(args[1]), args[2], Integer.parseInt(args[3]));
    }
}
