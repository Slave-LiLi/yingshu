package io.yingshu.framekey;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/** Deterministic adversarial inputs; no device or Android runtime is needed. */
public final class UvcFuzzTest {
    private static final Random RANDOM = new Random(0x59615556L);

    private static byte[] descriptorSeed() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(new byte[] {9,4,1,0,1,14,2,0,0});
        bytes.write(new byte[] {11,36,6,1,1,0,1,0,0,0,0});
        byte[] frame = new byte[38];
        frame[0]=38; frame[1]=36; frame[2]=7; frame[3]=1;
        frame[5]=0; frame[6]=5; frame[7]=(byte)0xD0; frame[8]=2;
        UvcDescriptors.put32(frame,17,1280L*720*2);
        UvcDescriptors.put32(frame,21,333333); frame[25]=3;
        UvcDescriptors.put32(frame,26,333333);
        UvcDescriptors.put32(frame,30,666666);
        UvcDescriptors.put32(frame,34,1000000);
        bytes.write(frame);
        return bytes.toByteArray();
    }

    public static void main(String[] args) throws Exception {
        byte[] seed = descriptorSeed();
        int descriptorCases=0, transferCases=0;
        for (int n=0;n<=seed.length;n++) {
            UvcDescriptors.parse(Arrays.copyOf(seed,n));
            descriptorCases++;
        }
        for(int iteration=0;iteration<20000;iteration++) {
            byte[] descriptors;
            if((iteration&1)==0) {
                descriptors=new byte[RANDOM.nextInt(257)];
                RANDOM.nextBytes(descriptors);
            } else {
                descriptors=seed.clone();
                for(int change=0;change<1+RANDOM.nextInt(6);change++)
                    descriptors[RANDOM.nextInt(descriptors.length)]=(byte)RANDOM.nextInt(256);
            }
            UvcDescriptors parsed=UvcDescriptors.parse(descriptors);
            for(UvcDescriptors.Mode mode:parsed.modes) {
                if(mode.width<=0||mode.height<=0||(long)mode.width*mode.height>8294400L)
                    throw new AssertionError("Unbounded descriptor dimensions");
                if(mode.chooseInterval(true)<=0||mode.chooseInterval(false)<=0)
                    throw new AssertionError("Nonpositive selected interval");
            }
            descriptorCases++;

            final int frameLimit=4+RANDOM.nextInt(1021);
            int payloadLimit=8+RANDOM.nextInt(249);
            AtomicInteger deliveries=new AtomicInteger();
            UvcPayloadAssembler assembler=new UvcPayloadAssembler(payloadLimit,frameLimit,jpeg->{
                if(jpeg.length<4||jpeg.length>frameLimit
                        ||(jpeg[0]&255)!=255||(jpeg[1]&255)!=216
                        ||(jpeg[jpeg.length-2]&255)!=255||(jpeg[jpeg.length-1]&255)!=217)
                    throw new AssertionError("Delivered frame violated bounds or markers");
                deliveries.incrementAndGet();
            });
            for(int part=0;part<12;part++) {
                int requested=1+RANDOM.nextInt(384), length=RANDOM.nextInt(requested+1);
                byte[] data=new byte[length]; RANDOM.nextBytes(data);
                assembler.accept(data,length,requested);
                transferCases++;
                if(RANDOM.nextInt(8)==0)assembler.discontinuity();
            }
            assembler.discontinuity();
            int prior=deliveries.get();
            // Two alternating, complete frames guarantee recovery regardless of
            // the damaged stream's last known FID or EOF state.
            byte[] first={2,(byte)0x82,(byte)255,(byte)216,(byte)255,(byte)217};
            byte[] second={2,(byte)0x83,(byte)255,(byte)216,(byte)255,(byte)217};
            assembler.accept(first,first.length,384);
            assembler.accept(second,second.length,384);
            if(deliveries.get()<=prior)throw new AssertionError("Failed to resynchronize after malformed stream");
        }
        System.out.println("PASS: "+descriptorCases+" descriptor inputs, "+transferCases
                +" randomized transfers, 20000 stream-recovery checks");
    }
}
