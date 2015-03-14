import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.*;
import java.util.ArrayList;
import java.util.Arrays;

public class Receiver2b {

    private final String TAG = "[" + Receiver2b.class.getSimpleName() + "]";
    private DatagramSocket serverSocket;
    private static final int DEBUG = 0;
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
        ByteArrayOutputStream baos = null;

        try {
            baos = new ByteArrayOutputStream();

            boolean canQuit = false;
            int lastPacketSequenceNumber = 0;
            int sequenceNum = 0;
            int windowBase = -1;
            int counterlol = 0;
            ArrayList<Integer> receivedSequenceNumbers = new ArrayList<Integer>();

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

                if (sequenceNum > windowBase && sequenceNum < windowBase + windowSize) {
                    // retrieve file bytes from packet bytes
                    for (int i = 3; i < packetBytes.length; i++) {
                        fileBytes[i - 3] = packetBytes[i];
                    }

                    if (!receivedSequenceNumbers.contains(sequenceNum)) {
                        receivedSequenceNumbers.add(sequenceNum);
                        // write file bytes to file
                        baos.write(fileBytes);
                    }

                    // shift window
                    if (sequenceNum == windowBase + 1) {
                        for (int i = windowBase + 1; i < windowBase + 1 + windowSize; i++) {
                            if (!receivedSequenceNumbers.contains(i)) {
                                windowBase = i - 1;
                                if (DEBUG == 1) System.out.println(TAG + " --------RECEIVER window base shifted to: " + windowBase);
                                break;
                            }
                        }
                    }

                    if (DEBUG == 1) System.out.println(TAG + " Received packet with sequence number: " + sequenceNum + " and flag: " + isLastPacket);

                    if (isLastPacket) {
                        lastPacketSequenceNumber = sequenceNum;
                    }

                    if (lastPacketSequenceNumber != 0) {
                        if (lastPacketSequenceNumber == receivedSequenceNumbers.size() - 1) {
                            // write all bytes to file and quit
                            writeToFile(filePath, baos.toByteArray());
                            canQuit = true;
                            System.out.println(TAG + " File received and saved successfully");
                            System.out.println(TAG + " " + Arrays.toString(receivedSequenceNumbers.toArray()));
                        }
                    }

                    sendAcknowledgment(sequenceNum, senderIPAddress, senderPort);
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
            if (baos != null) {
                try {
                    baos.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
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

    private static void writeToFile(String filePath, byte[] bytes) {
        File file = new File(filePath);
        if (file.exists()) {
            file.delete();
        }

        FileOutputStream fos = null;

        try {
            fos = new FileOutputStream(file);
            fos.write(bytes);

        } catch (Exception e) {
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
