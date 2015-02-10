import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;

public class Sender1a {

    private final String TAG = "[" + Sender1a.class.getSimpleName() + "]";  
    private DatagramSocket clientSocket;

    public Sender1a() {
        try {
            clientSocket = new DatagramSocket();

        } catch (SocketException e) {
            e.printStackTrace();
        }
    }

    public void sendFile(String destServerName, int destPort, String filePath) {
        System.out.println(TAG + " Started sending file");

        try {
            // get ip address of destination
            InetAddress destIPAddress = InetAddress.getByName(destServerName);

            // read file into a byte array
            File fileToSend = new File(filePath);
            byte[] fileBytes = getBytesFromFile(fileToSend);

            // divide file bytes into smaller packets of size 1024 bytes
            // and then send each of these packets to destination
            int sequenceNum = 0;
            boolean isLastPacket = false;

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

                sequenceNum++;
            }

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (clientSocket != null) {
                clientSocket.close();
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
        Sender1a sender = new Sender1a();
        sender.sendFile(args[0], Integer.parseInt(args[1]), args[2]);
    }
}
