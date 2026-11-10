import io.yingshu.framekey.KeyCatalog;
import io.yingshu.framekey.RuleBook;
import io.yingshu.framekey.RuleTextCodec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Portable text must retain HID identity and reject damaged imports without mutation. */
public final class RuleTextCodecTest {
    private static int checks;
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    private static String snapshot(RuleBook book){StringBuilder s=new StringBuilder();for(RuleBook.Rule r:book.rules)s.append(r.bindingId()).append('\n');return s.toString();}
    private static String withFirst(String binding){
        return RuleTextCodec.encode(new RuleBook()).replace("VK47|none=未分配","VK47|none="+binding);
    }
    private static void rejected(String text,String fragment){
        RuleBook book=new RuleBook();book.rules[0].usage=58;book.rules[0].modifiers=3;
        String before=snapshot(book);boolean rejected=false;
        try{RuleTextCodec.decode(text,book);}catch(IllegalArgumentException error){rejected=true;check(error.getMessage().contains(fragment),"helpful rejection: "+error.getMessage());}
        check(rejected,"invalid text rejected: "+fragment);check(snapshot(book).equals(before),"failure never mutates live rules");
    }
    private static void first(String binding,int usage,int modifiers){
        RuleTextCodec.Parsed parsed=RuleTextCodec.decode(withFirst(binding),new RuleBook());
        check(parsed.entries.get(0).usage==usage,"usage: "+binding);
        check(parsed.entries.get(0).modifiers==modifiers,"modifiers: "+binding);
    }
    public static void main(String[] args){
        RuleBook book=new RuleBook();
        String empty=RuleTextCodec.encode(book);
        RuleTextCodec.Parsed blank=RuleTextCodec.decode(empty,book);
        check(blank.entries.size()==38,"all combinations and unknown fallback included");
        check(blank.assignedCount==0,"unassigned count");
        check(blank.entries.get(37).id.equals("unknown_gun"),"explicit unknown identity");
        for(int i=0;i<KeyCatalog.USAGES.length;i++)for(int modifiers=0;modifiers<16;modifiers++){
            if(KeyCatalog.USAGES[i]==0&&modifiers!=0)continue;
            book.rules[0].usage=KeyCatalog.USAGES[i];book.rules[0].modifiers=modifiers;
            String before=snapshot(book);
            RuleTextCodec.Parsed parsed=RuleTextCodec.decode(RuleTextCodec.encode(book),book);
            check(parsed.entries.get(0).usage==KeyCatalog.USAGES[i],"every exported HID usage retained");
            check(parsed.entries.get(0).modifiers==modifiers,"every modifier mask retained");
            check(snapshot(book).equals(before),"successful parse is side effect free");
        }
        first("=",46,0);first("#",50,0);first("Ctrl+=",46,1);first("Win+#",50,8);
        first("小键盘 +",87,0);first("Ctrl+小键盘 +",87,1);first("ctrl + shift + 小键盘 +",87,3);
        first("ctrl+ALT+f24",115,5);first("Shift+Ctrl+F1",58,3);first("Ctrl+\\",49,1);
        first("小键盘 Enter",88,0);first("A",4,0);first("a",4,0);
        RuleTextCodec.Parsed windows=RuleTextCodec.decode("\ufeff\r\n# 备份\r\n"+empty.replace("\n","\r\n"),new RuleBook());
        check(windows.entries.size()==38,"BOM, CRLF, comments and blank lines accepted");
        List<String> rows=new ArrayList<String>();for(String line:empty.split("\n"))if(line.contains("=")&&!line.startsWith("#"))rows.add(line);
        Collections.reverse(rows);String shuffled=RuleTextCodec.HEADER+"\n"+String.join("\n",rows);
        RuleTextCodec.Parsed reordered=RuleTextCodec.decode(shuffled,new RuleBook());
        for(int i=0;i<book.rules.length;i++)check(reordered.entries.get(i).id.equals(book.rules[i].id),"text order cannot change rule identity");
        boolean immutable=false;try{reordered.entries.clear();}catch(UnsupportedOperationException expected){immutable=true;}check(immutable,"parsed entries immutable");
        book.rules[0].usage=4;book.rules[0].modifiers=1;
        RuleTextCodec.Parsed detached=RuleTextCodec.decode(RuleTextCodec.encode(book),book);book.rules[0].usage=115;
        check(detached.entries.get(0).usage==4,"parsed data detached from live rules");
        rejected(null,"粘贴");rejected(" ","粘贴");rejected("# 注释\n","缺少配置标识");
        rejected(empty.replace(RuleTextCodec.HEADER,"YINGSHU_KEYS_V2"),"配置标识");
        rejected(empty+"VK47|none=F1\n","规则重复");
        rejected(empty.replace("VK47|none=未分配","KRABER|none=F1"),"未知规则");
        rejected(empty.replace("VK47|none=未分配",""),"缺少规则");
        rejected(empty.replace("unknown_gun=未分配",""),"缺少规则");
        rejected(withFirst("Ctrl+未分配"),"未分配");rejected(withFirst("Ctrl+Ctrl+A"),"重复");
        rejected(withFirst("Ctrl+ctrl+A"),"重复");rejected(withFirst("Meta+F1"),"无效");
        rejected(withFirst("F25"),"无效按键");rejected(withFirst("12"),"无效按键");
        rejected(withFirst(""),"不能为空");rejected(withFirst("Ctrl+"),"缺少主按键");
        rejected(withFirst("Ctrl++F1"),"无效");rejected(withFirst("Ctrl+Shift"),"无效按键");
        rejected(withFirst("F1 # 注释"),"无效按键");rejected(withFirst("小键盘 + # 注释"),"无效");
        rejected(empty+"=F1\n","应为");rejected(empty+RuleTextCodec.HEADER+"\n","应为");
        StringBuilder tooLong=new StringBuilder(empty);while(tooLong.length()<=RuleTextCodec.MAX_TEXT_LENGTH)tooLong.append("# padding\n");rejected(tooLong.toString(),"过长");
        book.rules[0].usage=999;boolean badExport=false;try{RuleTextCodec.encode(book);}catch(IllegalArgumentException expected){badExport=true;}check(badExport,"invalid usage cannot silently export as unassigned");
        book.rules[0].usage=4;book.rules[0].modifiers=16;badExport=false;try{RuleTextCodec.encode(book);}catch(IllegalArgumentException expected){badExport=true;}check(badExport,"invalid modifier cannot export");
        book.rules[0].usage=0;book.rules[0].modifiers=1;badExport=false;try{RuleTextCodec.encode(book);}catch(IllegalArgumentException expected){badExport=true;}check(badExport,"unassigned modifier cannot export");
        System.out.println("RuleTextCodecTest passed: "+checks+" checks");
    }
}
