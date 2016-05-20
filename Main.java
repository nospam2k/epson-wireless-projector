package epson.projector.demo;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.*;

class Main {

    private int WIFI_PROJECTOR_NOT_CONNECTED = 0;
    private int WIFI_PROJECTOR_SEARCHING = 1;
    @SuppressWarnings("FieldCanBeLocal")
    private int WIFI_PROJECTOR_FOUND = 2;
    @SuppressWarnings("FieldCanBeLocal")
    private int WIFI_PROJECTOR_CONNECTED = 3;
    private int iWifiProjectorStatus = WIFI_PROJECTOR_NOT_CONNECTED;

    @SuppressWarnings("FieldCanBeLocal")
    private final Object socketLock = new Object();
    private DatagramSocket dsUDPSocket = null;
    private Socket sktControl;
    private Socket sktImage;
    private DataOutputStream dosCtlToProj;
    private DataOutputStream dosImgToProj;

    private boolean bKillingConnection = false;

    // UDP broadcast packet - Search
    @SuppressWarnings("FieldCanBeLocal")
    private byte[] baSearchUDP = new byte[] {0x45, 0x45, 0x4d, 0x50, 0x30, 0x31, 0x30, 0x30,
            0x00, 0x00, 0x00, 0x00, // PC Ip address
            0x01, 0x00, 0x00, 0x00, 0x30, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};

    // TCP control packet - Connect
    private byte[] baConnectTPC = new byte[] {0x45, 0x45, 0x4d, 0x50, 0x30, 0x31, 0x30, 0x30,
            0x00, 0x00, 0x00, 0x00,  // PC Ip address
            0x04, 0x00, 0x00, 0x00, 0x4a, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, (byte) 0xff, (byte) 0xff, (byte) 0xff, 0x00,
            0x00, 0x30, 0x00, 0x00, 0x02, 0x01, 0x01, 0x00, 0x04, 0x00, 0x03, 0x20, 0x20, 0x00, 0x01, (byte) 0xff,
            0x00, (byte) 0xff, 0x00, (byte) 0xff, 0x00, 0x00, 0x08, 0x10, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // Projector MAC address
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00}; // Epson Wifi projector IP address

    // TCP control packet - Keep Alive
    private byte[] baKeepAliveTCP = new byte[] { 0x45, 0x45, 0x4d, 0x50, 0x30, 0x31, 0x30, 0x30,
            0x00, 0x00, 0x00, 0x00, // PC Ip address
            0x0a, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};

    // TCP control packet - Disconnect
    private byte[] baDisconnectTCP = new byte[] {0x45, 0x45, 0x4d, 0x50, 0x30, 0x31, 0x30, 0x30,
            0x00, 0x00, 0x00, 0x00, // PC Ip address
            0x06, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};


    // TCP image packet - Init
    private byte[] baPreImage = new byte[]{0x45, 0x50, 0x52, 0x44, 0x30, 0x36, 0x30, 0x30,
            0x00, 0x00, 0x00, 0x00, // PC Ip address
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00}; // Image data in bytes

    // 1st part of TCP image packet - This packet works like how VNC does image updates. Can send image pieces.
    private byte[] baHeader = new byte[]{0x00, 0x00, 0x00,
            0x01,                                  // Number of images in packet (see info on packets)
            0x00, 0x00,                            // Pixel X-offset of image
            0x00, 0x00,                            // Pixel Y-offset of image
            0x04, 0x00,                            // Pixel Width of image
            0x03, 0x00,                            // Pixel Height of image
            0x00, 0x00, 0x00, 0x07, (byte) 0x90,
            0x00, 0x00, 0x00};                     // Image size modified by special calculation

    public static void main(String[] args) {
        new Main();
    }

    /**** Set this address to the ip of the machine running the demo ****/
    private String sAppIpAddress = "192.168.0.1";

    // Set Epson IP to broadcast for search
    private String sEpsonIpAddress = "255.255.255.255";

    private Main() {

        if (iWifiProjectorStatus == WIFI_PROJECTOR_NOT_CONNECTED) {
            try {
                byte[] baAppIpAddress = InetAddress.getByName(sAppIpAddress).getAddress();
                System.arraycopy(baAppIpAddress, 0, baSearchUDP, 8, baAppIpAddress.length);
                System.arraycopy(baAppIpAddress, 0, baConnectTPC, 8, baAppIpAddress.length);
                System.arraycopy(baAppIpAddress, 0, baKeepAliveTCP, 8, baAppIpAddress.length);
                System.arraycopy(baAppIpAddress, 0, baPreImage, 8, baAppIpAddress.length);
                System.arraycopy(baAppIpAddress, 0, baDisconnectTCP, 8, baAppIpAddress.length);

            } catch (UnknownHostException e) {
                e.printStackTrace();
            }

            iWifiProjectorStatus = WIFI_PROJECTOR_SEARCHING;

            // May not need to be synchronized but just in case
            synchronized (socketLock) {

                if (dsUDPSocket == null) {

                    try {

                        dsUDPSocket = new DatagramSocket(3620);

                        // Start receving UDP thread to capture response from projector
                        Runnable udpReceiveRunnable = new Runnable() {

                            @Override
                            public void run() {

                                // Start listening for incoming packets from projector
                                System.out.println("Listening for Epson Wifi projector");

                                if (dsUDPSocket != null) {

                                    byte[] data = new byte[1024];
                                    DatagramPacket packet = new DatagramPacket(data, data.length);

                                    try {

                                        // Keep looping while searching for projector
                                        while (iWifiProjectorStatus == WIFI_PROJECTOR_SEARCHING) {

                                            dsUDPSocket.receive(packet);

                                            if (packet.getLength() > 0 && dsUDPSocket != null) {

                                                // Skip packets that come from self
                                                if (!packet.getAddress().getHostAddress().equals(sAppIpAddress)) {

                                                    System.out.println("Received search response from "
                                                            + packet.getAddress().getHostAddress());

                                                    String sIncoming = new String(data);

                                                    // Check header to see if it's from the projector
                                                    if (sIncoming.contains("EEMP0100")
                                                            && iWifiProjectorStatus == WIFI_PROJECTOR_SEARCHING) {

                                                        System.out.println("Received packet from Epson Wifi projector");

                                                        // Update sEpsonIpAddress from broadcast to the projector IP
                                                        sEpsonIpAddress = packet.getAddress().getHostAddress();

                                                        // Copy that into the TCP connect packet
                                                        System.arraycopy(data, 68, baConnectTPC, 68, 6);
                                                        byte[] baEpsonIpAddress = InetAddress.getByName(
                                                                sEpsonIpAddress).getAddress();
                                                        System.arraycopy(baEpsonIpAddress, 0, baConnectTPC,
                                                                90, baEpsonIpAddress.length);
                                                    }

                                                    // Stop searching threads
                                                    iWifiProjectorStatus = WIFI_PROJECTOR_FOUND;

                                                    // Found projector so now establish a connection
                                                    makeTCPConnectionToEpsonWifiProjector();
                                                }
                                            }
                                        }
                                    } catch (IOException e) {
                                        if(!bKillingConnection) {
                                            e.printStackTrace();
                                            bKillingConnection = false;
                                        }
                                    } finally {

                                        // Close socket from search
                                        if (dsUDPSocket != null && !dsUDPSocket.isClosed()) {
                                            dsUDPSocket.close();
                                            dsUDPSocket = null;
                                        }
                                    }
                                }
                                System.out.println("End listening for Epson Wifi projector");
                            }
                        };
                        new Thread(udpReceiveRunnable).start();

                        // Timeout of 5 seconds in case we can't find the projector
                        long iTimer = System.currentTimeMillis();

                        // Loop for 5 seconds sending a search packet ever 500ms.
                        while (iWifiProjectorStatus == WIFI_PROJECTOR_SEARCHING &&
                                System.currentTimeMillis() < iTimer + 5000) {

                            if (dsUDPSocket != null) {

                                System.out.println("Search for Epson Wifi projector");

                                // Send broadcast search packet to 3620 (control port for Epson projector)
                                dsUDPSocket.send(new DatagramPacket(baSearchUDP,
                                        baSearchUDP.length,
                                        InetAddress.getByName(sEpsonIpAddress), 3620));

                                Thread.sleep(500);
                            }
                        }

                        // Didn't find a projector so stop searching (needed to kill listening thread)
                        if(iWifiProjectorStatus == WIFI_PROJECTOR_SEARCHING) {
                            iWifiProjectorStatus = WIFI_PROJECTOR_NOT_CONNECTED;
                            bKillingConnection = true;
                            dsUDPSocket.close();
                            dsUDPSocket = null;
                            System.out.println("Couldn't find Epson Wifi projector");
                            System.out.println("Demo complete");
                        }

                        // Finished search by timeout or found projector
                        System.out.println("End search for Epson Wifi projector");

                    } catch (IOException | InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    private void makeTCPConnectionToEpsonWifiProjector() {

        try {

            // Open TCP socket connection to projector
            sktControl = new Socket(sEpsonIpAddress, 3620);
            InputStream isCtl = sktControl.getInputStream();
            dosCtlToProj = new DataOutputStream(sktControl.getOutputStream());

            // Send TCP connect packet
            dosCtlToProj.write(baConnectTPC);

            // Wait for reply (could be more robust, Just looking for any response)
            while (!sktControl.isClosed()) {
                //noinspection ResultOfMethodCallIgnored - Ignore output
                isCtl.read();
                if (isCtl.available() > 0) {
                    break;
                }
            }

            // Start TCP keepalive thread because projector needs a ping every 5 seconds or so or it will
            // stop showing image after 1min.
            new Thread(new Runnable() {
                @Override
                public void run() {

                    long timer;

                    try {

                        System.out.println("KeepAlive started");

                        // Continuous loop every 5 seconds (could be done better I'm sure)
                        while (sktControl != null && !sktControl.isClosed()) {
                            timer = System.currentTimeMillis();
                            dosCtlToProj.write(baKeepAliveTCP);
                            Thread.sleep(5000 - (System.currentTimeMillis() - timer));
                        }

                    } catch (SocketTimeoutException e) {
                        if (e.getMessage().contains("timed out")) {
                            System.out.println("KeepAlive timed out");
                        } else {
                            e.printStackTrace();
                        }
                    } catch (IOException | NullPointerException | InterruptedException e) {
                        e.printStackTrace();
                    }

                    System.out.println("KeepAlive ended");
                }
            }).start();

            // Just a 1 second delay to make sure the projector is connected and keep alive is going.
            Thread.sleep(1000);

            // Connect to image TCP Socket
            sktImage = new Socket(sEpsonIpAddress, 3621);
            dosImgToProj = new DataOutputStream(sktImage.getOutputStream());

            System.out.println( "Epson Wifi projector connected");
            iWifiProjectorStatus = WIFI_PROJECTOR_CONNECTED;

            sendImageToEpsonProjector();

        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void sendImageToEpsonProjector() {
        try {

            // NOTE image will be displayed relative to the size of the image and according
            // to the X, Y offsets in baHeader
            // For the demo, we will just use one image 1024x768 which is the max size of an
            // Epson EX5220 projector image
            int iImageHeight = 768; // Needs to be less then the max for the projector
            int iImageWidth = 1024; // Needs to be less then the max for the projector

            // Just a green image. Modify this for whatever BufferedImage is required.
            // NOTE: Image MUST be RGB565
            BufferedImage tmpBufferedImage = new BufferedImage(iImageWidth, iImageHeight,
                    BufferedImage.TYPE_USHORT_565_RGB);
            Graphics2D g2 = tmpBufferedImage.createGraphics();
            g2.setColor(Color.GREEN);
            g2.fillRect(0,0, iImageWidth, iImageHeight);
            g2.dispose();

            // Prepare image packet for sending
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(tmpBufferedImage, "jpg", baos);
            byte[] bImage = baos.toByteArray();

            // add Image width
            baHeader[8] = (byte) (iImageWidth >> 8);
            baHeader[9] = (byte) iImageWidth;

            // add Image height
            baHeader[10] = (byte) (iImageHeight >> 8);
            baHeader[11] = (byte) iImageHeight;

            // Calc size into special
            int iSize = bImage.length;
            baHeader[17] = (byte) ((iSize) | 0x80);
            baHeader[18] =  (byte) ((iSize >> 7) | 0x80);
            baHeader[19] = (byte) ((iSize >> 14));

            // In here you could set X, Y offset (which are left at 0 for the demo) and create other image
            // fragments and append to image packet (see info on packets) but for the demo only one image is used

            // Update baPreImage with size of image in bytes
            baPreImage[18] = (byte) (bImage.length >> 8);
            baPreImage[19] = (byte) (bImage.length);

            // This will be the final packet with the header and image combined
            byte[] bOutput = new byte[baHeader.length + bImage.length];
            System.arraycopy(baHeader, 0, bOutput, 0, baHeader.length);
            System.arraycopy(bImage, 0, bOutput, baHeader.length, bImage.length);

            // Send bPreImage
            dosImgToProj.write(baPreImage);

            // Send image packet which should be displayed on the screen
            dosImgToProj.write(bOutput);

            // Just hang out for 10 seconds and then disconnect to complete demo
            Thread.sleep(10000);

            disconnect();

            System.out.println("Demo complete");

        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void disconnect() {
        try {

            // Projector can be a bit finicky so loop sending disconnect packets until the socket closes
            while(sktControl != null && !sktControl.isClosed()) {
                try {
                    dosCtlToProj.write(baDisconnectTCP);
                } catch (IOException e) {
                    if(!e.toString().toLowerCase().contains("broken pipe")) {
                        System.out.println( "Problem clearing TCP ports");
                    }
                    break;
                }
                Thread.sleep(500);
            }

            // Close the control port
            if(sktControl != null && !sktControl.isClosed()) {
                System.out.println( "Close port 3620");
                sktControl.close();
            }
            sktControl = null;

            // Close the image port
            if(sktImage != null && !sktImage.isClosed()) {
                System.out.println( "Close port 3621");
                sktImage.close();
            }
            sktImage = null;

            iWifiProjectorStatus = WIFI_PROJECTOR_NOT_CONNECTED;

        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
    }
}
