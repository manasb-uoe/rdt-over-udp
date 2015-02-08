import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.*;

public class Sender1b {

    private final String TAG = "[" + Sender1b.class.getSimpleName() + "]";
    private DatagramSocket clientSocket;

    public Sender1b() {
        try {
            clientSocket = new DatagramSocket();
        } catch (SocketException e) {
            e.printStackTrace();
        }
    }

    public void sendFile(String destServerName, int destPort, String filePath, int retryTimeout) throws IOException {
        System.out.println(TAG + " Started sending file");
        // get ip address of destination
        InetAddress destIPAddress = InetAddress.getByName(destServerName);

        // read file into a byte array
        File fileToSend = new File(filePath);
        byte[] fileBytes = getBytesFromFile(fileToSend);

        // get start time
        long startTime = System.currentTimeMillis();

        // divide file bytes into smaller packets of size 1024 bytes
        // and then send each of these packets to destination
        int sequenceNum = 0;
        boolean isLastPacket = false;

        // counter for counting number of retransmissions
        int retransmissionCounter = 0;

        // counter for counting number of times last packet is sent
        int lastPacketTransmissionCounter = 0;

        for (int i=0; i<fileBytes.length; i+=1021) {
            byte[] packetBytes = new byte[1024];

            // add 16 bit sequence number as first 2 bytes
            packetBytes[0] = (byte) (sequenceNum >> 8);
            packetBytes[1] = (byte) (sequenceNum);

            // add last message flag as 3rd byte
            isLastPacket = i+1021 >= fileBytes.length;
            packetBytes[2] = isLastPacket ? (byte) 1 : (byte) 0;

            // add file bytes to remaining 1021 bytes
            if (!isLastPacket) {
                for (int j=0; j<1021; j++) {
                    packetBytes[j+3] = fileBytes[i+j];
                }
            }
            else {
                // if last packet, only write remaining bytes instead of 1021
                for (int j=0; j<fileBytes.length-i; j++) {
                    packetBytes[j+3] = fileBytes[i+j];
                }
            }

            // finally, send the packet to destination
            DatagramPacket packetToSend = new DatagramPacket(packetBytes, packetBytes.length, destIPAddress, destPort);
            clientSocket.send(packetToSend);

            // wait for acknowledgment from receiver. If positive acknowledgment is received, i.e.
            // acknowledgementSequenceNum == sequenceNum, then continue and send next packet.
            // Else, resend current packet.
            boolean postiveAcknowledgmentReceived = false;
            boolean acknowledgementReceived = false;

            while(!postiveAcknowledgmentReceived) {
                byte[] acknowledgementPacketBytes = new byte[2];

                DatagramPacket acknowledgmentPacket = new DatagramPacket(acknowledgementPacketBytes, acknowledgementPacketBytes.length);
                try {
                    clientSocket.setSoTimeout(retryTimeout);
                    clientSocket.receive(acknowledgmentPacket);
                    acknowledgementReceived = true;
                } catch (SocketTimeoutException e) {
                    System.out.println(TAG + " Socket timed out while waiting for acknowledgment");
                    acknowledgementReceived = false;
                }

                // retrieve sequence number from acknowledgement bytes
                int acknowledgementSequenceNum = ((acknowledgementPacketBytes[0] & 0xff) << 8) + (acknowledgementPacketBytes[1] & 0xff);

                if (acknowledgementSequenceNum == sequenceNum && acknowledgementReceived) {
                    postiveAcknowledgmentReceived = true;
                    System.out.println(TAG + " Received Acknowledgment with sequence number: " + acknowledgementSequenceNum);
                    break;
                }
                else {  // resend the same packet since negative acknowledgement received
                    clientSocket.send(packetToSend);
                    retransmissionCounter++;
                    
                    System.out.println(TAG + " Resending packet with sequence number: " + sequenceNum);
                    
                    // send last packet 100 more times before closing socket 
                    if (isLastPacket) {
                        lastPacketTransmissionCounter++;
                        if (lastPacketTransmissionCounter > 100) {
                            postiveAcknowledgmentReceived = true;
                            System.out.println(TAG + " Sender has given up sending the last packet now");
                        }
                        else {
                            System.out.println(TAG + " Sending last packet. Attempt #" + lastPacketTransmissionCounter);
                        }
                    }
                }
            }

            // keep incrementing the sequence number that gets sent along with with the packet
            sequenceNum++;
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

        } catch (Exception e) {
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

    public static void main(String[] args) {
        Sender1b sender = new Sender1b();
        try {
            sender.sendFile(args[0], Integer.parseInt(args[1]), args[2], Integer.parseInt(args[3]));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
