package spatch;

import android.content.Context;
import android.content.res.loader.ResourcesLoader;
import android.content.res.loader.ResourcesProvider;
import android.os.ParcelFileDescriptor;
import java.io.*;

/** API 30 resource overlay: only the patched XML and palette entries override installed resources. */
final class PaletteResources {
    private static ResourcesLoader current;

    /** The new overlay loads before the old one goes, so a failure leaves the running theme whole. */
    static boolean swap(Context context,String theme,boolean baked){
        ResourcesLoader previous=current;
        if(!baked && !install(context,theme))return false;
        if(previous!=null)context.getResources().removeLoaders(previous);
        if(baked)current=null;
        return true;
    }

    /** A rebuilt screen keeps the Resources it had, loaders included, so each one is brought over itself. */
    static void sync(android.content.res.Resources resources){
        try{
            resources.clearLoaders();
            if(current!=null)resources.addLoaders(current);
        }catch(RuntimeException e){
            android.util.Log.i("Spatch","screen overlay: "+e); // a rebuilt screen normally inherits it from the application
        }
    }

    static boolean install(Context context,String theme){
        File file=null;
        try{
            file=File.createTempFile("spatch-theme-",".apk",context.getCacheDir());
            try(InputStream in=context.getAssets().open("spatch-themes/"+theme+".apk");OutputStream out=new FileOutputStream(file)){
                byte[] buffer=new byte[16384];int count;while((count=in.read(buffer))!=-1)out.write(buffer,0,count);
            }
            ResourcesLoader loader=new ResourcesLoader();
            try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY)){
                loader.addProvider(ResourcesProvider.loadFromApk(fd));
            }
            context.getResources().addLoaders(loader);
            current=loader;
            return true;
        }catch(IOException|RuntimeException e){return false;}
        finally{if(file!=null)file.delete();}
    }
}
