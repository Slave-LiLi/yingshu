package io.yingshu.framekey;

import java.util.ArrayList;
import java.util.List;

/** Keyboard usage values understood by the M3 protocol. Zero means unassigned. */
public final class KeyCatalog {
    public static final String[] NAMES;
    public static final int[] USAGES;
    static {
        List<String> names = new ArrayList<>(); List<Integer> codes = new ArrayList<>();
        add(names, codes, "未分配", 0);
        for (int i=0;i<26;i++) add(names,codes,String.valueOf((char)('A'+i)),4+i);
        for (int i=1;i<=9;i++) add(names,codes,String.valueOf(i),29+i);
        add(names,codes,"0",39);
        for (int i=1;i<=12;i++) add(names,codes,"F"+i,57+i);
        for (int i=13;i<=24;i++) add(names,codes,"F"+i,0x68+i-13);
        String[] special={"Enter","Esc","Backspace","Tab","Space","-","=","[","]","\\","#",";","'","`",",",".","/","CapsLock"};
        for(int i=0;i<special.length;i++) add(names,codes,special[i],40+i);
        String[] navigation={"PrintScreen","ScrollLock","Pause","Insert","Home","PageUp","Delete","End","PageDown","RightArrow","LeftArrow","DownArrow","UpArrow","NumLock","小键盘 /","小键盘 *","小键盘 -","小键盘 +","小键盘 Enter"};
        for(int i=0;i<navigation.length;i++) add(names,codes,navigation[i],70+i);
        for(int i=1;i<=9;i++) add(names,codes,"小键盘 "+i,88+i);
        add(names,codes,"小键盘 0",98);add(names,codes,"小键盘 .",99);
        NAMES=names.toArray(new String[0]);USAGES=new int[codes.size()];
        for(int i=0;i<USAGES.length;i++) USAGES[i]=codes.get(i);
    }
    private static void add(List<String> names,List<Integer> codes,String name,int code){names.add(name);codes.add(code);}
    public static int indexOf(int usage){for(int i=0;i<USAGES.length;i++)if(USAGES[i]==usage)return i;return 0;}
    public static String name(int usage,int modifiers){
        if(usage==0)return "未分配";
        StringBuilder result=new StringBuilder();
        if((modifiers&1)!=0)result.append("Ctrl+");if((modifiers&2)!=0)result.append("Shift+");
        if((modifiers&4)!=0)result.append("Alt+");if((modifiers&8)!=0)result.append("Win+");
        return result.append(NAMES[indexOf(usage)]).toString();
    }
    private KeyCatalog(){}
}
