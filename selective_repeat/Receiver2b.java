import java.io.*;
import java.net.*;
import java.util.*;

public class Receiver2b {

    private static final String TAG = "[" + Receiver2b.class.getSimpleName() + "]";
    private static final int DEBUG = 0;

    private DatagramSocket serverSocket;
    private String filePath;
    private int windowSize;

    public Receiver2b(int port, String filePath, int windowSize) {
        this.filePath = filePath;
        this.windowSize = windowSize;

        try {
            serverSocket = new DatagramSocket(port);
            serverSocket.setSoTimeout(5000);
        } catch (SocketException e) {
            e.printStackTrace();
        }
    }

    public void receiveFile() {
        try {
            boolean canQuit = false;
            int lastPacketSequenceNumber = 0;
            int sequenceNum = 0;
            int windowBase = -1;
            HashMap<Integer, byte[]> map = new HashMap<Integer, byte[]>();

            while (!canQuit) {
                // create separate byte arrays for full packet bytes and file bytes (without header)
                byte[] packetBytes = new byte[1024];
                byte[] fileBytes = new byte[1021];

                // receive packet and retrieve file and header bytes from it
                DatagramPacket receivedPacket = new DatagramPacket(packetBytes, packetBytes.length);
                serverSocket.receive(receivedPacket);

                // retrieve sequence number from header
                sequenceNum = ((packetBytes[0] & 0xFF) << 8) + (packetBytes[1] & 0xFF);

                // retrieve last message flag
                boolean isLastPacket = (packetBytes[2] & 0xFF) == 1;

                // get port and address of sender
                InetAddress senderIPAddress = receivedPacket.getAddress();
                int senderPort = receivedPacket.getPort();

                // if packet sequence number is within window, the packet is correctly received
                // i.e. the packet is buffered, we check if the window can be shifted or not,
                // and an acknowledgment is sent back to the client
                if (sequenceNum > windowBase && sequenceNum <= (windowBase + windowSize)) {
                    // retrieve file bytes from packet bytes
                    for (int i = 3; i < packetBytes.length; i++) {
                        fileBytes[i - 3] = packetBytes[i];
                    }

                    map.put(sequenceNum, fileBytes);

                    // shift window if received packet is first in the window
                    if (sequenceNum == windowBase + 1) {
                        while (true) {
                            if (map.containsKey(windowBase+1)) {
                            	windowBase++;
                            } else {
                            	break;
                            }
                        }
                    }

                    if (DEBUG == 1) System.out.println(TAG + " Received packet with sequence number: " + sequenceNum + " and flag: " + isLastPacket);

                    // when the packet with last flag is received, save its sequence number
                    if (isLastPacket) {
                        lastPacketSequenceNumber = sequenceNum;
                    }

                    // if packet with last flag has already been received, and all other packets before that have also
                    // been received, then write bytes to file and quit
                    if (lastPacketSequenceNumber != 0) {
                        if (lastPacketSequenceNumber == map.size() - 1) {
                            writeToFile(filePath, map);
                            canQuit = true;
                            System.out.println(TAG + " File received and saved successfully");
                        }
                    }

                    sendAcknowledgment(sequenceNum, senderIPAddress, senderPort);

                // else if packet sequence number is within [rcv_base-N, rcv_base-1], then an acknowledgment is sent back to
                // to the client, however the packet is not buffered
                } else if (sequenceNum > windowBase - windowSize && sequenceNum < windowBase + 1) {
                    sendAcknowledgment(sequenceNum, senderIPAddress, senderPort);
                }
            }
        } catch (SocketTimeoutException e) {
            if (DEBUG == 1) System.out.println(TAG + " Receiver timed out.");
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (serverSocket != null) {
                serverSocket.close();
            }
        }
    }

    public void sendAcknowledgment(int sequenceNum, InetAddress address, int port) {
        byte[] acknowledgementPacketBytes = new byte[2];

        acknowledgementPacketBytes[0] = (byte) (sequenceNum >> 8);
        acknowledgementPacketBytes[1] = (byte) (sequenceNum);

        try {
            DatagramPacket acknowledgement = new DatagramPacket(acknowledgementPacketBytes, acknowledgementPacketBytes.length, address, port);
            serverSocket.send(acknowledgement);
        } catch (IOException e) {
            e.printStackTrace();
        }

        if (DEBUG == 1) System.out.println(TAG + " Sent acknowledgment with sequence number: " + sequenceNum);
    }

    private static void writeToFile(String filePath, HashMap<Integer, byte[]> receivedPacketsMap) {
        File file = new File(filePath);
        if (file.exists()) {
            file.delete();
        }

        // merge all byte arrays before writing to file
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ArrayList<Integer> sortedKeys = new ArrayList<Integer>(receivedPacketsMap.keySet());
        Collections.sort(sortedKeys);
        for (int i : sortedKeys) {
            try {
                baos.write(receivedPacketsMap.get(i));
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        FileOutputStream fos = null;

        // finally, write the merged byte array to file
        try {
            fos = new FileOutputStream(file);
            fos.write(baos.toByteArray());
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                if (fos != null) {
                    fos.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public static void main(final String[] args) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                Receiver2b server = new Receiver2b(Integer.parseInt(args[0]), args[1], Integer.parseInt(args[2]));
                server.receiveFile();
            }
        });
        thread.start();
    }
}
