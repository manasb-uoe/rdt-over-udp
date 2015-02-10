import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;

public class Receiver1a {

    private final String TAG = "[" + Receiver1a.class.getSimpleName() + "]";
    private DatagramSocket serverSocket;    

    public Receiver1a(int port) {
        try {
            serverSocket = new DatagramSocket(port);
        } catch (SocketException e) {
            e.printStackTrace();
        }
    }

    public void receiveFile(String filePath) {
        ByteArrayOutputStream baos = null;

        try {
            baos = new ByteArrayOutputStream();

            int sequenceNum = 0;
            boolean isLastPacket = false;

            while (!isLastPacket) {
                // create separate byte arrays for full packet bytes and file bytes (without header)
                byte[] packetBytes = new byte[1024];
                byte[] fileBytes = new byte[1021];

                // receive packet and retrieve file and header bytes from it
                DatagramPacket receivedPacket = new DatagramPacket(packetBytes, packetBytes.length);
                serverSocket.receive(receivedPacket);

                // retrieve sequence number from header
                sequenceNum = ((packetBytes[0] & 0xFF) << 8) + (packetBytes[1] & 0xFF);

                // retrieve last message flag
                isLastPacket = (packetBytes[2] & 0xFF) == 1;

                // retrieve file bytes from packet bytes
                for (int i=3; i<packetBytes.length; i++) {
                    fileBytes[i-3] = packetBytes[i];
                }

                // write file bytes to file
                baos.write(fileBytes);

                System.out.println("Sent: Sequence number = " + sequenceNum + ", Flag = " + isLastPacket);

                // if last packet reached, write all bytes to file and quit
                if (isLastPacket) {
                    serverSocket.close();
                    writeToFile(filePath, baos.toByteArray());
                    baos.close();
                    break;
                }
            }

            System.out.println(TAG + " File received and saved successfully");

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
        }
    }
    
    public static void writeToFile(String filePath, byte[] bytes) {
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
        Receiver1a server = new Receiver1a(Integer.parseInt(args[0]));
        server.receiveFile(args[1]);
    }
}
