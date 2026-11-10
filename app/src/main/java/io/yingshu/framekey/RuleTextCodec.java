package io.yingshu.framekey;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Complete, versioned key mappings. Parsing never changes live rules or preferences. */
public final class RuleTextCodec {
    public static final String HEADER="YINGSHU_KEYS_V1";
    public static final int MAX_TEXT_LENGTH=65536;

    public static final class Entry {
        public final String id;
        public final int usage,modifiers;
        private Entry(String id,int usage,int modifiers){this.id=id;this.usage=usage;this.modifiers=modifiers;}
    }
    public static final class Parsed {
        public final List<Entry> entries;
        public final int assignedCount;
        private Parsed(List<Entry> source){
            entries=Collections.unmodifiableList(new ArrayList<Entry>(source));
            int assigned=0;for(Entry entry:entries)if(entry.usage!=0)assigned++;
            assignedCount=assigned;
        }
    }

    public static String encode(RuleBook book){
        catalog(book);
        StringBuilder out=new StringBuilder(4096);
        out.append(HEADER).append('\n');
        out.append("# 映枢自定义枪械按键 · ").append(book.rules.length).append(" 项\n");
        out.append("# 保留全部规则；只修改等号后的按键。支持 Ctrl/Shift/Alt/Win 组合。\n");
        out.append("# 未分配表示不发送按键。请整段复制，不要在规则行后附加注释。\n\n");
        for(RuleBook.Rule rule:book.rules){
            validateBinding(rule.usage,rule.modifiers,"规则 "+rule.id);
            out.append("# ").append(rule.title()).append('\n');
            out.append(rule.id).append('=').append(KeyCatalog.name(rule.usage,rule.modifiers)).append('\n');
        }
        return out.toString();
    }

    public static Parsed decode(String text,RuleBook book){
        Map<String,RuleBook.Rule> expected=catalog(book);
        if(text==null)throw new IllegalArgumentException("请粘贴完整的按键配置文本");
        if(text.length()>MAX_TEXT_LENGTH)throw new IllegalArgumentException("配置文本过长，最多支持 65536 个字符");
        if(text.trim().isEmpty())throw new IllegalArgumentException("请粘贴完整的按键配置文本");
        if(text.charAt(0)=='\ufeff')text=text.substring(1);
        String[] lines=text.split("\\r\\n|\\n|\\r",-1);
        boolean header=false;
        Map<String,Entry> parsed=new LinkedHashMap<String,Entry>();
        for(int i=0;i<lines.length;i++){
            String line=lines[i].trim();int number=i+1;
            if(line.isEmpty()||line.startsWith("#"))continue;
            if(!header){
                if(!HEADER.equals(line))throw error(number,"配置标识不正确，请使用映枢导出的完整文本");
                header=true;continue;
            }
            int equal=line.indexOf('=');
            if(equal<=0)throw error(number,"应为 规则ID=按键");
            String id=line.substring(0,equal).trim();
            String binding=line.substring(equal+1).trim();
            if(!expected.containsKey(id))throw error(number,"未知规则："+id);
            if(parsed.containsKey(id))throw error(number,"规则重复："+id);
            parsed.put(id,parseBinding(id,binding,number));
        }
        if(!header)throw new IllegalArgumentException("缺少配置标识 "+HEADER);
        List<Entry> ordered=new ArrayList<Entry>(expected.size());
        for(RuleBook.Rule rule:book.rules){
            Entry entry=parsed.get(rule.id);
            if(entry==null)throw new IllegalArgumentException("缺少规则："+rule.title()+"（"+rule.id+"）。请粘贴完整 "+book.rules.length+" 项配置");
            ordered.add(entry);
        }
        return new Parsed(ordered);
    }

    private static Entry parseBinding(String id,String binding,int line){
        if(binding.isEmpty())throw error(line,"按键不能为空；不发送请填写“未分配”");
        int modifiers=0;
        String remaining=binding;
        while(true){
            int index=keyIndex(remaining);
            if(index>=0){
                int usage=KeyCatalog.USAGES[index];
                if(usage==0&&modifiers!=0)throw error(line,"“未分配”不能带修饰键");
                return new Entry(id,usage,modifiers);
            }
            int plus=remaining.indexOf('+');
            if(plus<0)throw error(line,"无效按键："+remaining);
            String modifier=remaining.substring(0,plus).trim();
            int mask=modifierMask(modifier);
            if(mask==0)throw error(line,"无效修饰键或按键："+modifier);
            if((modifiers&mask)!=0)throw error(line,"修饰键重复："+modifier);
            modifiers|=mask;remaining=remaining.substring(plus+1).trim();
            if(remaining.isEmpty())throw error(line,"修饰键后缺少主按键");
        }
    }
    private static int modifierMask(String name){
        if("Ctrl".equalsIgnoreCase(name))return 1;
        if("Shift".equalsIgnoreCase(name))return 2;
        if("Alt".equalsIgnoreCase(name))return 4;
        if("Win".equalsIgnoreCase(name))return 8;
        return 0;
    }
    private static int keyIndex(String name){
        for(int i=0;i<KeyCatalog.NAMES.length;i++)if(KeyCatalog.NAMES[i].equalsIgnoreCase(name))return i;
        return -1;
    }
    private static void validateBinding(int usage,int modifiers,String where){
        boolean known=false;for(int code:KeyCatalog.USAGES)if(code==usage){known=true;break;}
        if(!known)throw new IllegalArgumentException(where+" 的按键编号无效");
        if(modifiers<0||(modifiers&~15)!=0)throw new IllegalArgumentException(where+" 的修饰键无效");
        if(usage==0&&modifiers!=0)throw new IllegalArgumentException(where+" 的未分配按键不能带修饰键");
    }
    private static Map<String,RuleBook.Rule> catalog(RuleBook book){
        if(book==null)throw new IllegalArgumentException("按键规则未就绪");
        Map<String,RuleBook.Rule> rules=new LinkedHashMap<String,RuleBook.Rule>();
        for(RuleBook.Rule rule:book.rules){
            if(rule==null||rule.id==null||rules.put(rule.id,rule)!=null)throw new IllegalArgumentException("按键规则表无效");
        }
        return rules;
    }
    private static IllegalArgumentException error(int line,String message){return new IllegalArgumentException("第 "+line+" 行："+message);}
    private RuleTextCodec(){}
}
