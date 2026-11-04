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
        if (resources == null) return;
        try{
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                java.util.List<ResourcesLoader> loaders = resources.getLoaders();
                if (loaders != null && !loaders.isEmpty()) {
                    resources.removeLoaders(loaders.toArray(new ResourcesLoader[loaders.size()]));
                }
                if(current!=null) resources.addLoaders(current);
            }
        }catch(Throwable e){
            android.util.Log.i("Spatch","screen overlay: "+e); // a rebuilt screen normally inherits it from the application
        }
    }

    /** The Lavender overlay rewritten in the wallpaper's colours; each entry keeps its compression. */
    private static void recolored(InputStream in,OutputStream out,int[] row) throws IOException{
        java.util.zip.ZipInputStream zin=new java.util.zip.ZipInputStream(in);
        java.util.zip.ZipOutputStream zout=new java.util.zip.ZipOutputStream(out);
        for(java.util.zip.ZipEntry e;(e=zin.getNextEntry())!=null;){
            ByteArrayOutputStream body=new ByteArrayOutputStream();
            byte[] buffer=new byte[16384];int count;while((count=zin.read(buffer))!=-1)body.write(buffer,0,count);
            byte[] bytes=body.toByteArray();
            String name=e.getName();
            if(name.equals("resources.arsc")||name.endsWith(".xml")&&!name.equals("AndroidManifest.xml"))bytes=Palette.recolor(bytes,row);
            else if(name.endsWith(".json"))bytes=Palette.recolorAnimation(bytes,row[6]);
            java.util.zip.ZipEntry n=new java.util.zip.ZipEntry(name);
            n.setMethod(e.getMethod());
            if(e.getMethod()==java.util.zip.ZipEntry.STORED){
                java.util.zip.CRC32 crc=new java.util.zip.CRC32();crc.update(bytes);
                n.setSize(bytes.length);n.setCompressedSize(bytes.length);n.setCrc(crc.getValue());
            }
            zout.putNextEntry(n);zout.write(bytes);zout.closeEntry();
        }
        zout.finish();
    }

    /**
     * The overlay is prepared once and kept in the cache, named for the theme, this install and (for the
     * wallpaper theme) its colours, so a launch only maps it; preparing it cost 30-60ms of startup.
     */
    static boolean install(Context context,String theme){
        try{
            int[] row=Palette.selectedRow(theme);
            long installed=context.getPackageManager().getPackageInfo(context.getPackageName(),0).lastUpdateTime;
            String key=theme+"-"+Long.toHexString(installed)+("-"+Integer.toHexString(java.util.Arrays.hashCode(row)));
            File file=new File(context.getCacheDir(),"spatch-theme-"+key+".apk");
            if(!file.isFile()){
                File[] old=context.getCacheDir().listFiles();
                if(old!=null)for(File f:old)if(f.getName().startsWith("spatch-theme-"))f.delete();
                File part=new File(context.getCacheDir(),"spatch-theme-"+key+".part");
                try(InputStream in=context.getAssets().open("spatch-themes/lavender.apk");OutputStream out=new FileOutputStream(part)){
                    recolored(in,out,row);
                }
                if(!part.renameTo(file))return false;
            }
            ResourcesLoader loader=new ResourcesLoader();
            try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY)){
                loader.addProvider(ResourcesProvider.loadFromApk(fd));
            }
            context.getResources().addLoaders(loader);
            current=loader;
            return true;
        }catch(IOException|RuntimeException|android.content.pm.PackageManager.NameNotFoundException e){return false;}
    }
}
