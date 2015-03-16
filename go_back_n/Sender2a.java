import java.io.*;
import java.net.*;
import java.util.ArrayList;

public class Sender2a {

    private final String TAG = "[" + Sender2a.class.getSimpleName() + "]";
    private DatagramSocket clientSocket;
    private static int IDEAL_RETRY_TIMEOUT = 50;

    public Sender2a() {
        try {
            clientSocket = new DatagramSocket();
            clientSocket.setSoTimeout(IDEAL_RETRY_TIMEOUT);
        } catch (SocketException e) {
            e.printStackTrace();
        }
    }

    public void sendFile(String destServerName, int destPort, String filePath, int windowSize) {
        System.out.println(TAG + " Started sending file");

        // get ip address of destination
        InetAddress destIPAddress = null;
        try {
            destIPAddress = InetAddress.getByName(destServerName);
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }

        // read file into a byte array
        File fileToSend = new File(filePath);
        byte[] fileBytes = getBytesFromFile(fileToSend);

        long startTime = System.currentTimeMillis();    // start time for calculating throughput
        int sequenceNum = 0;                            // sequence number of packet to be sent
        int retransmissionCounter = 0;                  // counter for retransmissions
        int windowBase = -1;                            // sequence number of last acknowledged packet (base of window)

        // prepare a list of all packets to be sent
        ArrayList<byte[]> allPacketsList = prepareAllPackets(fileBytes);

        while (sequenceNum < allPacketsList.size()) {
            byte[] packetBytes = allPacketsList.get(sequenceNum);
            DatagramPacket packetToSend = new DatagramPacket(packetBytes, packetBytes.length, destIPAddress, destPort);

            if (sequenceNum <= windowBase + windowSize) {   // if pipeline is not full
                try {
                    clientSocket.send(packetToSend);
                    System.out.println(TAG + " Sent packet with sequence number: " + sequenceNum);

                    sequenceNum++;
                } catch (IOException e) {
                    e.printStackTrace();
                }
            } else {  // if pipeline is full
                while (true) {
                    boolean ackReceived = false;
                    byte[] ackBytes = new byte[2];

                    DatagramPacket ackPacket = new DatagramPacket(ackBytes, ackBytes.length);
                    int ackSequenceNum = 0;

                    try {
                        clientSocket.receive(ackPacket);
                        ackSequenceNum = ((ackBytes[0] & 0xff) << 8) + (ackBytes[1] & 0xff);
                        ackReceived = true;
                    } catch (SocketTimeoutException e) {
                        System.out.println(TAG + " Socket timed out while waiting for acknowledgment");
                        ackReceived = false;
                    } catch (SocketException e) {
                        e.printStackTrace();
                    } catch (IOException e) {
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
                    } else {
                        for (int j = windowBase+1; j < sequenceNum; j++) {
                            byte[] packetToResendBytes = allPacketsList.get(j);
                            DatagramPacket packetToResend = new DatagramPacket(packetToResendBytes, packetToResendBytes.length, destIPAddress, destPort);

                            try {
                                clientSocket.send(packetToResend);
                                retransmissionCounter += 1;

                                System.out.println(TAG + " Resending packet with sequence number: " + j);
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                    }
                }
            }
        }

        // continue to receive acknowledgements until last acknowledgement is received
        boolean isLastAckPacket = false;
        int resendCounter = 0;

        while (!isLastAckPacket) {
            boolean ackReceived = false;
            byte[] ackBytes = new byte[2];

            DatagramPacket ackPacket = new DatagramPacket(ackBytes, ackBytes.length);
            int ackSequenceNum = 0;

            try {
                clientSocket.receive(ackPacket);
                ackSequenceNum = ((ackBytes[0] & 0xff) << 8) + (ackBytes[1] & 0xff);
                ackReceived = true;
            } catch (SocketTimeoutException e) {
                System.out.println(TAG + " Socket timed out while waiting for acknowledgment");
                ackReceived = false;
            } catch (SocketException e) {
                e.printStackTrace();
            } catch (IOException e) {
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
                // set isLastAckPacket to true so that we can break from the while loop and close the socket
                if (ackSequenceNum == allPacketsList.size() - 1) {
                    isLastAckPacket = true;
                    System.out.println(TAG + " Received final acknowledgment, now shutting down.");
                }

                // reset resend counter every time an acknowledgment is received
                resendCounter = 0;

            } else {
                resendCounter++;

                if (resendCounter < 20) {
                    for (int j = windowBase+1; j < sequenceNum; j++) {
                        byte[] packetToResendBytes = allPacketsList.get(j);
                        DatagramPacket packetToResend = new DatagramPacket(packetToResendBytes, packetToResendBytes.length, destIPAddress, destPort);

                        try {
                            clientSocket.send(packetToResend);
                            retransmissionCounter += 1;

                            System.out.println(TAG + " Resending packet with sequence number: " + j);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                } else {
                    isLastAckPacket = true;
                    System.out.println(TAG + " Let assume we received final acknowledgment, now shutting down. LOL");
                    break;
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

    private static ArrayList<byte[]> prepareAllPackets(byte[] fileBytes) {
        ArrayList<byte[]> allPacketsList = new ArrayList<byte[]>();
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
        Sender2a sender = new Sender2a();
        sender.sendFile(args[0], Integer.parseInt(args[1]), args[2], Integer.parseInt(args[3]));
    }
}
