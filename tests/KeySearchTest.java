import io.yingshu.framekey.KeyCatalog;
import io.yingshu.framekey.KeySearch;
import java.util.List;

/** Verify searches cannot change persisted keyboard identities or invent a selection. */
public final class KeySearchTest {
    private static int checks;
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    private static void first(String query,String name,int usage){
        List<KeySearch.Entry> entries=KeySearch.find(query);
        check(!entries.isEmpty(),"matched "+query);
        check(entries.get(0).name.equals(name),"best match "+query);
        check(entries.get(0).usage==usage,"usage "+query);
    }
    public static void main(String[] args){
        first("f1","F1",58);first("F10","F10",67);first("Ｆ２４","F24",115);
        first("1","1",30);first("回车","Enter",40);first("空格","Space",44);
        first("删除","Delete",76);first("退格","Backspace",42);first("上","UpArrow",82);
        first("page up","PageUp",75);first("未分配","未分配",0);
        first("小键盘 1","小键盘 1",89);first("NUMPAD 0","小键盘 0",98);
        first("数字键盘回车","小键盘 Enter",88);first("小键盘 +","小键盘 +",87);
        first("/","/",56);first("-","-",45);
        check(KeySearch.find("no-such-key-92351").isEmpty(),"unmatched query cannot pick an unrelated key");
        check(KeySearch.find("").size()==KeyCatalog.NAMES.length,"empty query restores all keys");
        check(KeySearch.find(null).size()==KeyCatalog.NAMES.length,"null query restores all keys");
        for(String query:new String[]{"","f1","小键盘","回车","1","右"," ","none"}){
            for(KeySearch.Entry entry:KeySearch.find(query)){
                check(KeyCatalog.USAGES[entry.catalogIndex]==entry.usage,"catalog usage preserved after "+query);
                check(KeyCatalog.NAMES[entry.catalogIndex].equals(entry.name),"catalog name preserved after "+query);
            }
        }
        List<KeySearch.Entry> all=KeySearch.find("");all.clear();
        check(KeySearch.find("").size()==KeyCatalog.NAMES.length,"caller cannot mutate future search results");
        System.out.println("KeySearchTest passed: "+checks+" checks");
    }
}
