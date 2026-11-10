package io.yingshu.framekey;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Search results retain catalog identity; their position is never a keyboard usage. */
public final class KeySearch {
    public static final class Entry {
        public final int catalogIndex, usage;
        public final String name, label;
        private final String[] aliases;
        private Entry(int index,String aliases) {
            catalogIndex=index;usage=KeyCatalog.USAGES[index];name=KeyCatalog.NAMES[index];
            this.aliases=aliases.isEmpty()?new String[0]:aliases.split("\\|");
            label=this.aliases.length==0?"":this.aliases[0];
        }
        public String displayName(){return label.isEmpty()?name:name+" · "+label;}
    }
    private static final Entry[] ALL;
    static {
        ALL=new Entry[KeyCatalog.NAMES.length];
        for(int i=0;i<ALL.length;i++)ALL[i]=new Entry(i,aliases(KeyCatalog.NAMES[i]));
    }
    private static String aliases(String name) {
        switch(name){
            case "未分配":return "不发送|无按键|清除|unassigned|none";
            case "Enter":return "回车|返回|return";
            case "Esc":return "退出|取消|escape";
            case "Backspace":return "退格|删除前一个|back";
            case "Tab":return "制表|tab键";
            case "Space":return "空格|spacebar";
            case "CapsLock":return "大写锁定|caps";
            case "PrintScreen":return "截屏|截图|打印屏幕|prtsc|prtscr";
            case "ScrollLock":return "滚动锁定|scrlk";
            case "Pause":return "暂停|break";
            case "Insert":return "插入|ins";
            case "Home":return "行首|首页";
            case "PageUp":return "上一页|向上翻页|上翻页|pgup";
            case "Delete":return "删除|del";
            case "End":return "行尾|末尾";
            case "PageDown":return "下一页|向下翻页|下翻页|pgdn";
            case "RightArrow":return "右方向键|右箭头|向右|右|right";
            case "LeftArrow":return "左方向键|左箭头|向左|左|left";
            case "DownArrow":return "下方向键|下箭头|向下|下|down";
            case "UpArrow":return "上方向键|上箭头|向上|上|up";
            case "NumLock":return "数字锁定|小键盘锁定|numlk";
            case "-":return "减号|横线|minus";
            case "=":return "等号|equal";
            case "[":return "左方括号";
            case "]":return "右方括号";
            case "\\":return "反斜杠|backslash";
            case "#":return "井号|hash";
            case ";":return "分号|semicolon";
            case "'":return "单引号|quote";
            case "`":return "反引号|backtick";
            case ",":return "逗号|comma";
            case ".":return "句号|点|period";
            case "/":return "斜杠|slash";
            default:
                if(name.startsWith("小键盘 ")){
                    String key=name.substring(4);
                    if("Enter".equals(key))return "小键盘回车|数字键盘回车|numpad enter|keypad enter";
                    return "数字键盘 "+key+"|numpad "+key+"|keypad "+key;
                }
                return "";
        }
    }
    private static String normalize(String value) {
        if(value==null)return "";
        return Normalizer.normalize(value,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("\\s+","");
    }
    private static int match(String value,String query){
        String normalized=normalize(value);
        if(normalized.equals(query))return 0;
        if(normalized.startsWith(query))return 1;
        return normalized.contains(query)?2:3;
    }
    private static int score(Entry entry,String query){
        int best=match(entry.name,query);
        for(String alias:entry.aliases)best=Math.min(best,match(alias,query));
        return best;
    }
    public static List<Entry> find(String query){
        String normalized=normalize(query);List<Entry> result=new ArrayList<>();
        for(Entry entry:ALL)if(normalized.isEmpty()||score(entry,normalized)<3)result.add(entry);
        if(!normalized.isEmpty())result.sort(Comparator.comparingInt((Entry entry)->score(entry,normalized)).thenComparingInt(entry->entry.catalogIndex));
        return result;
    }
    private KeySearch(){}
}
