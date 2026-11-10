package io.yingshu.framekey;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class UvcProtocolTest {
    private static int checks;
    private static final byte[] JPEG = {(byte)255,(byte)216,1,2,(byte)255,(byte)217};
    private static void check(boolean pass, String name) {
        if (!pass) throw new AssertionError(name);
        checks++;
    }
    private static byte[] payload(int flags, byte[] jpeg) {
        byte[] result = new byte[jpeg.length + 2];
        result[0] = 2; result[1] = (byte)flags;
        System.arraycopy(jpeg,0,result,2,jpeg.length);
        return result;
    }
    private static UvcPayloadAssembler make(List<byte[]> frames, int maxPayload, int maxFrame) {
        return new UvcPayloadAssembler(maxPayload,maxFrame,frames::add);
    }
    public static void main(String[] args) throws Exception {
        List<byte[]> out = new ArrayList<>();
        UvcPayloadAssembler a = make(out,64,128);
        byte[] packet = payload(0x82,JPEG);
        a.accept(packet,packet.length,64);
        check(out.size()==1 && Arrays.equals(out.get(0),JPEG),"short payload EOF");

        out.clear(); a=make(out,64,128);
        byte[] pts = {6,(byte)0x86,1,2,3,4,(byte)255,(byte)216,1,2,(byte)255,(byte)217};
        for (int p=0;p<pts.length;p+=3) a.accept(Arrays.copyOfRange(pts,p,p+3),3,3);
        check(out.isEmpty(),"full transfers do not prematurely close a payload");
        a.accept(new byte[0],0,3);
        check(out.size()==1,"split header and ZLP terminator");

        out.clear(); a=make(out,8,128);
        a.accept(Arrays.copyOfRange(packet,0,4),4,4);
        a.accept(Arrays.copyOfRange(packet,4,8),4,4);
        check(out.size()==1,"maximum payload split across reads");

        out.clear(); a=make(out,8,128);
        byte[] multi = {2,(byte)0x80,(byte)255,(byte)216,1,2,3,4,2,(byte)0x82,5,6,7,8,(byte)255,(byte)217};
        a.accept(multi,multi.length,multi.length);
        check(out.size()==1 && out.get(0).length==12,"multiple payloads inside one transfer");

        out.clear(); a=make(out,64,128);
        packet=payload(0x80,JPEG); a.accept(packet,packet.length,64);
        packet=payload(0x83,JPEG); a.accept(packet,packet.length,64);
        check(out.size()==2,"FID transition completes previous JPEG without EOF");

        out.clear(); a=make(out,64,128);
        packet=payload(0xc2,JPEG); a.accept(packet,packet.length,64);
        packet=payload(0x83,JPEG); a.accept(packet,packet.length,64);
        check(out.size()==1 && a.errorPayloads==1 && a.droppedFrames==1,"ERR flag drops frame and recovers");

        out.clear(); a=make(out,64,128);
        packet=payload(0x86,JPEG); a.accept(packet,packet.length,64);
        packet=payload(0x83,JPEG); a.accept(packet,packet.length,64);
        check(out.size()==1 && a.invalidPayloads==1,"PTS flag validates header length");

        out.clear(); a=make(out,64,8);
        packet=payload(0x82,new byte[]{(byte)255,(byte)216,1,2,3,4,5,6,7,8,(byte)255,(byte)217});
        a.accept(packet,packet.length,64);
        packet=payload(0x83,JPEG); a.accept(packet,packet.length,64);
        check(out.size()==1 && a.droppedFrames==1,"oversize frame bounded and next frame recovers");

        out.clear(); a=make(out,64,128);
        packet=payload(0x82,new byte[]{(byte)255,(byte)216,1,2}); a.accept(packet,packet.length,64);
        check(out.isEmpty() && a.droppedFrames==1,"truncated JPEG is not delivered");

        out.clear(); a=make(out,64,128);
        a.accept(new byte[]{0,0,0},3,64);
        a.accept(new byte[]{1},1,64);
        a.accept(new byte[0],0,64);
        packet=payload(0x83,JPEG); a.accept(packet,packet.length,64);
        check(out.size()==1 && a.invalidPayloads==2,"zero and one-byte headers cannot loop or overrun");

        out.clear(); a=make(out,64,128);
        packet=payload(0x80,new byte[]{(byte)255,(byte)216,1}); a.accept(packet,packet.length,64);
        a.discontinuity();
        packet=payload(0x83,JPEG); a.accept(packet,packet.length,64);
        check(out.size()==1,"USB discontinuity resets partial payload");

        boolean rejected=false;
        try { a.accept(new byte[1],2,2); } catch(IllegalArgumentException expected) { rejected=true; }
        check(rejected,"invalid Java transfer bounds rejected");

        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        raw.write(new byte[]{9,4,0,0,0,14,1,0,0});
        raw.write(new byte[]{5,36,1,16,1});
        raw.write(new byte[]{9,4,1,0,1,14,2,0,0});
        raw.write(new byte[]{11,36,6,1,2,0,1,0,0,0,0});
        raw.write(frameDescriptor(1,640,480)); raw.write(frameDescriptor(2,1280,720));
        UvcDescriptors d = UvcDescriptors.parse(raw.toByteArray());
        check(d.version==0x0110 && !d.malformed && d.modes.size()==2,"MJPEG descriptors decoded");
        check(d.preferredModes(1).isEmpty(),"720p and 480p cannot silently replace required full-HD");
        raw.write(frameDescriptor(3,1920,1080));
        d=UvcDescriptors.parse(raw.toByteArray());
        UvcDescriptors.Mode mode = d.preferredModes(1).get(0);
        check(d.preferredModes(1).size()==1 && mode.width==1920 && mode.height==1080,"only 1920x1080 is selectable");
        check(d.preferredModes(2).isEmpty(),"full-HD mode must belong to chosen interface");
        check(!UvcDescriptors.isFullHd(d.find(1,1,2).width,d.find(1,1,2).height),"negotiated lower frame index is identifiable and rejected");
        check(!UvcDescriptors.isFullHd(1080,1920) && !UvcDescriptors.isFullHd(1920,720),"actual JPEG dimensions must match exact full-HD");
        check(mode.chooseInterval(false)==333333 && mode.chooseInterval(true)==1000000,"normal and eco device intervals");
        check(mode.width==1920 && mode.height==1080,"eco changes frame interval without changing full-HD dimensions");
        UvcDescriptors.Mode continuous = new UvcDescriptors.Mode(1,1,1,640,480,614400,333333,new long[0],100000,2000000,300000);
        check(continuous.chooseInterval(false)==400000,"continuous interval rounds upward to supported step");
        check(continuous.chooseInterval(true)==1000000,"continuous eco interval matches supported step");
        check(UvcDescriptors.parse(new byte[]{1,4,0}).malformed,"malformed descriptor bounded");
        check(UvcDescriptors.parse(new byte[]{9,4,0}).modes.isEmpty(),"truncated interface rejected");
        System.out.println("PASS: " + checks + " UVC protocol boundary checks");
    }

    private static byte[] frameDescriptor(int index,int width,int height) {
        byte[] d=new byte[34]; d[0]=34; d[1]=36; d[2]=7; d[3]=(byte)index;
        d[5]=(byte)width;d[6]=(byte)(width>>8);d[7]=(byte)height;d[8]=(byte)(height>>8);
        UvcDescriptors.put32(d,17,(long)width*height*2);
        UvcDescriptors.put32(d,21,333333);d[25]=2;
        UvcDescriptors.put32(d,26,333333);UvcDescriptors.put32(d,30,1000000);
        return d;
    }
}
