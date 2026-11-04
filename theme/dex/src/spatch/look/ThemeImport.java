package spatch;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Fragment;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;

/** A bounded document import; both variants are validated before any preference changes. */
public final class ThemeImport extends Fragment {
    private Runnable done;
    private static final String[] ROLES={"surface","elevated","highlight","text","secondary","subdued","accent","accent_highlight","accent_pressed"};

    static void start(Context context,Runnable done){
        while(context instanceof ContextWrapper && !(context instanceof Activity))context=((ContextWrapper)context).getBaseContext();
        if(!(context instanceof Activity))return;
        ThemeImport picker=new ThemeImport();picker.done=done;
        ((Activity)context).getFragmentManager().beginTransaction().add(picker,"spatch-theme-import").commit();
    }

    public void onCreate(android.os.Bundle saved){
        super.onCreate(saved);
        if(saved!=null){finish();return;}
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        try{startActivityForResult(intent,0x5f03);}catch(RuntimeException error){fail("No file picker is available.");}
    }

    public void onActivityResult(int request,int result,Intent data){
        if(request!=0x5f03)return;
        if(result!=Activity.RESULT_OK || data==null || data.getData()==null){finish();return;}
        final Activity activity=getActivity();
        if(activity==null){finish();return;}
        final android.net.Uri uri=data.getData();
        new Thread(new Runnable(){public void run(){
            try(java.io.InputStream in=activity.getContentResolver().openInputStream(uri)){
                if(in==null)throw new IllegalArgumentException("Couldn't open the theme.");
                java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;
                while((n=in.read(buffer))!=-1){if(bytes.size()+n>262144)throw new IllegalArgumentException("Theme files must be under 256 KiB.");bytes.write(buffer,0,n);}
                final String source=java.nio.charset.StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString();
                final int[][] rows=parse(source);
                activity.runOnUiThread(new Runnable(){public void run(){review(rows);}});
            }catch(final Exception error){activity.runOnUiThread(new Runnable(){public void run(){fail(error.getMessage()==null?"Invalid theme file.":error.getMessage());}});}
        }},"SpatchThemeImport").start();
    }

    private void review(final int[][] rows){
        final Activity activity=getActivity();if(activity==null){finish();return;}
        new AlertDialog.Builder(activity,Palette.dialogTheme()).setTitle("Import theme?").setMessage("The file contains valid dark and light palettes.")
            .setPositiveButton("Import",new android.content.DialogInterface.OnClickListener(){public void onClick(android.content.DialogInterface dialog,int which){
                if(!Palette.saveCustom(rows[0],rows[1],"Imported theme")){fail("Couldn't save the theme.");return;}
                Palette.choose("custom");
                if(done!=null)done.run();
                if(!Palette.apply(activity,"custom"))new AlertDialog.Builder(activity,Palette.dialogTheme()).setTitle("Restart to apply theme?")
                    .setMessage("The imported palette is saved.").setPositiveButton("Restart now",new android.content.DialogInterface.OnClickListener(){public void onClick(android.content.DialogInterface d,int w){Palette.restart(activity);}}).setNegativeButton("Later",null).show();
                finish();
            }}).setNegativeButton("Cancel",new android.content.DialogInterface.OnClickListener(){public void onClick(android.content.DialogInterface d,int w){finish();}}).show();
    }

    private void fail(String message){Activity activity=getActivity();if(activity!=null)new AlertDialog.Builder(activity,Palette.dialogTheme()).setTitle("Couldn't import theme").setMessage(message).setPositiveButton("OK",null).show();finish();}
    private void finish(){done=null;if(getFragmentManager()!=null)getFragmentManager().beginTransaction().remove(this).commitAllowingStateLoss();}

    static int[][] parse(String source) throws Exception {
        source=source.trim();if(source.startsWith("\ufeff"))source=source.substring(1).trim();
        Map<String,String> dark=new HashMap<String,String>(),light=new HashMap<String,String>();
        if(source.startsWith("{")){
            JSONObject root=new JSONObject(source);
            for(int i=0;i<4;i++){
                JSONObject nested=root.optJSONObject("colors");if(nested==null)nested=root.optJSONObject("schemes");if(nested==null)nested=root.optJSONObject("default");
                if(nested==null)break;root=nested;
            }
            JSONObject darkObject=root.optJSONObject("dark"),lightObject=root.optJSONObject("light");
            json(darkObject==null?root:darkObject,dark,"dark");json(lightObject==null?root:lightObject,light,"light");
        }else{
            Map<String,String> current=null;
            for(String raw:source.split("\n")){
                String line=raw.trim();if(line.isEmpty()||line.startsWith("#"))continue;
                if(line.startsWith("[")){
                    String section=line.replaceFirst("\\s*#.*$","").trim();
                    if(section.matches("\\[(?:(?:colors|schemes)\\.)?dark\\]"))current=dark;
                    else if(section.matches("\\[(?:(?:colors|schemes)\\.)?light\\]"))current=light;
                    else throw new IllegalArgumentException("Use [dark] and [light] theme sections.");
                    continue;
                }
                java.util.regex.Matcher pair=java.util.regex.Pattern.compile("^([A-Za-z_][A-Za-z0-9_-]*)\\s*=\\s*(\"[^\"]*\"|'[^']*'|0x[0-9a-fA-F]+)\\s*(?:#.*)?$").matcher(line);
                if(!pair.matches())throw new IllegalArgumentException("Expected a quoted hex colour: "+line);
                String key=pair.group(1),value=pair.group(2);
                if(current==null){if(!key.equals("name"))throw new IllegalArgumentException("Colours must be inside [dark] or [light].");continue;}
                if(value.startsWith("\"")||value.startsWith("'"))value=value.substring(1,value.length()-1);
                if(current.put(key,value)!=null)throw new IllegalArgumentException("Duplicate colour: "+key);
            }
        }
        return new int[][]{variant(dark,"dark"),variant(light,"light")};
    }

    private static void json(JSONObject source,Map<String,String> target,String mode) throws Exception {
        if(source==null)return;
        for(java.util.Iterator<String> keys=source.keys();keys.hasNext();){
            String key=keys.next();Object value=source.get(key);
            for(int depth=0;value instanceof JSONObject && depth<6;depth++){
                JSONObject object=(JSONObject)value;Object next=null;
                for(String wrapper:new String[]{mode,"default","hex","color","value"})if(object.has(wrapper)){next=object.get(wrapper);break;}
                if(next==null)break;value=next;
            }
            key=key.replaceAll("([a-z0-9])([A-Z])","$1_$2").toLowerCase(java.util.Locale.ROOT).replaceFirst("^m3_","");
            target.put(key,String.valueOf(value));
        }
    }

    static int[] variant(Map<String,String> colours,String name){
        String[][] aliases={{"surface","background"},{"elevated","surface_container_low","surface_container"},{"highlight","surface_container_highest","surface_container_high"},{"text","on_surface","on_background"},{"secondary","on_surface_variant"},{"subdued","outline","on_surface_variant"},{"accent","primary"},{"accent_highlight"},{"accent_pressed"}};
        int[] row=new int[9];
        for(int i=0;i<row.length;i++){
            String value=null;for(String key:aliases[i])if(colours.containsKey(key)){value=colours.get(key);break;}
            if(value==null && i>=7){row[i]=Pill.blend(0xff000000|row[6],i==7?0xffffffff:0xff000000,i==7?.25f:.15f)&0xffffff;continue;}
            if(value==null)throw new IllegalArgumentException("Missing "+name+" colour: "+ROLES[i]);
            value=value.replaceFirst("^(#|0x)","");
            if(!value.matches("[0-9a-fA-F]{6}"))throw new IllegalArgumentException(name+" "+ROLES[i]+" must be #RRGGBB.");
            row[i]=Integer.parseInt(value,16);
        }
        if(contrast(row[3],row[0])<4.5 || contrast(row[3],row[2])<4.5 || contrast(row[0],row[6])<4.5)
            throw new IllegalArgumentException("The "+name+" text and accent need at least 4.5:1 contrast with their backgrounds.");
        return row;
    }
    private static double luminance(int rgb){double sum=0;double[] weights={.2126,.7152,.0722};for(int i=0;i<3;i++){double c=((rgb>>(16-i*8))&255)/255.0;sum+=weights[i]*(c<=.03928?c/12.92:Math.pow((c+.055)/1.055,2.4));}return sum;}
    private static double contrast(int a,int b){double x=luminance(a),y=luminance(b);return (Math.max(x,y)+.05)/(Math.min(x,y)+.05);}
}
