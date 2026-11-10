package io.yingshu.framekey;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Versioned local agreement asset. Loading it never starts application services. */
public final class AppAgreement {
    public static final String TITLE="映枢开源许可与使用说明";
    public static final String VERSION="2026-11-10-agpl-v2";
    private static final String ASSET="licenses/Yingshu-Source-Use-Agreement.txt";
    private static final int MAX_BYTES=65536;

    public static String read(Context context) throws IOException {
        try(InputStream input=context.getAssets().open(ASSET);
            ByteArrayOutputStream bytes=new ByteArrayOutputStream(4096)){
            byte[] buffer=new byte[4096];int total=0,count;
            while((count=input.read(buffer))!=-1){
                if(count==0)continue;
                if(count>MAX_BYTES-total)throw new IOException("协议文件过大");
                bytes.write(buffer,0,count);total+=count;
            }
            final String text;
            try{
                text=StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
            }catch(CharacterCodingException error){throw new IOException("协议文件不是有效的 UTF-8 文本",error);}
            String normalized=text.replace("\r\n","\n");
            if(!normalized.startsWith(TITLE+"\n")||!normalized.contains("版本："+VERSION+"\n"))
                throw new IOException("协议文件内容或版本不匹配");
            return text;
        }
    }
    private AppAgreement(){}
}
