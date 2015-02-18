import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;

public class Receiver2a {

    private final String TAG = "[" + Receiver2a.class.getSimpleName() + "]";
    private DatagramSocket serverSocket;

    public Receiver2a(int port) {
        try {
            serverSocket = new DatagramSocket(port);
            serverSocket.setSoTimeout(5000);
        }
        catch (SocketException e) {
            e.printStackTrace();
        }
    }

    public void receiveFile(String filePath) {
        ByteArrayOutputStream baos = null;

        try {
            baos = new ByteArrayOutputStream();

            int sequenceNum = 0;
            int previousSequenceNum = -1;
            boolean canQuit = false;

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

                if (sequenceNum == (previousSequenceNum + 1)) {
                    previousSequenceNum = sequenceNum;

                    // retrieve file bytes from packet bytes
                    for (int i=3; i<packetBytes.length; i++) {
                        fileBytes[i-3] = packetBytes[i];
                    }

                    // write file bytes to file
                    baos.write(fileBytes);

                    System.out.println(TAG + " Received packet with sequence number: " + previousSequenceNum +" and flag: " + isLastPacket);

                    if (isLastPacket) {
                        // write all bytes to file and quit      
                        writeToFile(filePath, baos.toByteArray());
                        canQuit = true;
                        System.out.println(TAG + " File received and saved successfully");
                    }
                } 
                else {
                    System.out.println(TAG + " Expected sequence number: " + (previousSequenceNum + 1) + " but received " + sequenceNum);
                }

                // get port and address of sender and send acknowledgement (positive or negative, depending on the received packet)
                InetAddress senderIPAddress = receivedPacket.getAddress();
                int senderPort = receivedPacket.getPort();
                sendAcknowledgment(previousSequenceNum, senderIPAddress, senderPort);
            }
        }
        catch (SocketTimeoutException e) {
            System.out.println(TAG + " Receiver timed out.");
            System.exit(1);
        }  
        catch (IOException e) {
            e.printStackTrace();
        } 
        finally {
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
            DatagramPacket acknowledgement = new  DatagramPacket(acknowledgementPacketBytes, acknowledgementPacketBytes.length, address, port);
            serverSocket.send(acknowledgement);
        }
        catch (IOException e) {
            e.printStackTrace();
        }

        System.out.println(TAG + " Sent acknowledgment with sequence number: " + sequenceNum);
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

        }
        catch (Exception e) {
            e.printStackTrace();
        }
        finally {
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
        Receiver2a server = new Receiver2a(Integer.parseInt(args[0]));
        server.receiveFile(args[1]);        
    }
}
