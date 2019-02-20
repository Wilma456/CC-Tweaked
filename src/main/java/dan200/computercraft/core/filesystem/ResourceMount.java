/*
 * This file is part of ComputerCraft - http://www.computercraft.info
 * Copyright Daniel Ratcliffe, 2011-2019. Do not distribute without permission.
 * Send enquiries to dratcliffe@gmail.com
 */

package dan200.computercraft.core.filesystem;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.io.ByteStreams;
import dan200.computercraft.api.filesystem.IMount;
import dan200.computercraft.core.apis.handles.ArrayByteChannel;
import net.minecraft.resources.IReloadableResourceManager;
import net.minecraft.resources.IResource;
import net.minecraft.resources.IResourceManager;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.resource.IResourceType;
import net.minecraftforge.resource.ISelectiveResourceReloadListener;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

public class ResourceMount implements IMount
{
    /**
     * Only cache files smaller than 1MiB.
     */
    private static final int MAX_CACHED_SIZE = 1 << 20;

    /**
     * Limit the entire cache to 64MiB.
     */
    private static final int MAX_CACHE_SIZE = 64 << 20;

    private static final byte[] TEMP_BUFFER = new byte[8192];

    /**
     * We maintain a cache of the contents of all files in the mount. This allows us to allow
     * seeking within ROM files, and reduces the amount we need to access disk for computer startup.
     */
    private static final Cache<FileEntry, byte[]> CONTENTS_CACHE = CacheBuilder.newBuilder()
        .concurrencyLevel( 4 )
        .expireAfterAccess( 60, TimeUnit.SECONDS )
        .maximumWeight( MAX_CACHE_SIZE )
        .weakKeys()
        .<FileEntry, byte[]>weigher( ( k, v ) -> v.length )
        .build();

    private final String namespace;
    private final String subPath;
    private final IReloadableResourceManager manager;

    @Nullable
    private FileEntry root;

    public ResourceMount( String namespace, String subPath, IReloadableResourceManager manager )
    {
        this.namespace = namespace;
        this.subPath = subPath;
        this.manager = manager;

        this.manager.addReloadListener( new Listener( this ) );
    }

    private void load()
    {
        boolean hasAny = false;
        FileEntry newRoot = new FileEntry( new ResourceLocation( namespace, subPath ) );
        for( ResourceLocation file : manager.getAllResourceLocations( subPath, s -> true ) )
        {
            if( !file.getNamespace().equals( namespace ) ) continue;

            String localPath = FileSystem.toLocal( file.getPath(), subPath );
            create( newRoot, localPath );
            hasAny = true;
        }

        root = hasAny ? newRoot : null;
    }

    private FileEntry get( String path )
    {
        FileEntry lastEntry = root;
        int lastIndex = 0;

        while( lastEntry != null && lastIndex < path.length() )
        {
            int nextIndex = path.indexOf( '/', lastIndex );
            if( nextIndex < 0 ) nextIndex = path.length();

            lastEntry = lastEntry.children == null ? null : lastEntry.children.get( path.substring( lastIndex, nextIndex ) );
            lastIndex = nextIndex + 1;
        }

        return lastEntry;
    }

    private void create( FileEntry lastEntry, String path )
    {
        int lastIndex = 0;
        while( lastIndex < path.length() )
        {
            int nextIndex = path.indexOf( '/', lastIndex );
            if( nextIndex < 0 ) nextIndex = path.length();

            String part = path.substring( lastIndex, nextIndex );
            if( lastEntry.children == null ) lastEntry.children = new HashMap<>();

            FileEntry nextEntry = lastEntry.children.get( part );
            if( nextEntry == null )
            {
                lastEntry.children.put( part, nextEntry = new FileEntry( new ResourceLocation( namespace, subPath + "/" + path ) ) );
            }

            lastEntry = nextEntry;
            lastIndex = nextIndex + 1;
        }
    }

    @Override
    public boolean exists( @Nonnull String path )
    {
        return get( path ) != null;
    }

    @Override
    public boolean isDirectory( @Nonnull String path )
    {
        FileEntry file = get( path );
        return file != null && file.isDirectory();
    }

    @Override
    public void list( @Nonnull String path, @Nonnull List<String> contents ) throws IOException
    {
        FileEntry file = get( path );
        if( file == null || !file.isDirectory() ) throw new IOException( "/" + path + ": Not a directory" );

        file.list( contents );
    }

    @Override
    public long getSize( @Nonnull String path ) throws IOException
    {
        FileEntry file = get( path );
        if( file != null )
        {
            if( file.size != -1 ) return file.size;
            if( file.isDirectory() ) return file.size = 0;

            byte[] contents = CONTENTS_CACHE.getIfPresent( file );
            if( contents != null ) return file.size = contents.length;

            try
            {
                IResource resource = manager.getResource( file.identifier );
                InputStream s = resource.getInputStream();
                int total = 0, read = 0;
                do
                {
                    total += read;
                    read = s.read( TEMP_BUFFER );
                } while( read > 0 );

                return file.size = total;
            }
            catch( IOException e )
            {
                return file.size = 0;
            }
        }

        throw new IOException( "/" + path + ": No such file" );
    }

    @Nonnull
    @Override
    @Deprecated
    public InputStream openForRead( @Nonnull String path ) throws IOException
    {
        return Channels.newInputStream( openChannelForRead( path ) );
    }

    @Nonnull
    @Override
    public ReadableByteChannel openChannelForRead( @Nonnull String path ) throws IOException
    {
        FileEntry file = get( path );
        if( file != null && !file.isDirectory() )
        {
            byte[] contents = CONTENTS_CACHE.getIfPresent( file );
            if( contents != null ) return new ArrayByteChannel( contents );

            try( InputStream stream = manager.getResource( file.identifier ).getInputStream() )
            {
                if( stream.available() > MAX_CACHED_SIZE ) return Channels.newChannel( stream );

                contents = ByteStreams.toByteArray( stream );
                CONTENTS_CACHE.put( file, contents );
                return new ArrayByteChannel( contents );
            }
            catch( FileNotFoundException ignored )
            {
            }
        }

        throw new IOException( "/" + path + ": No such file" );
    }

    private class FileEntry
    {
        final ResourceLocation identifier;
        Map<String, FileEntry> children;
        long size = -1;

        FileEntry( ResourceLocation identifier )
        {
            this.identifier = identifier;
        }

        boolean isDirectory()
        {
            return children != null;
        }

        void list( List<String> contents )
        {
            if( children != null ) contents.addAll( children.keySet() );
        }
    }

    /**
     * A {@link ISelectiveResourceReloadListener} which refers to the {@link ResourceMount} weakly.
     *
     * While people should really be keeping a permanent reference to this, some people construct it every
     * method call, so let's make this as small as possible.
     *
     * TODO: Make this a shared one instead, which reloads all mounts.
     */
    static class Listener implements ISelectiveResourceReloadListener
    {
        private final WeakReference<ResourceMount> ref;

        Listener( ResourceMount mount )
        {
            this.ref = new WeakReference<>( mount );
        }

        @Override
        public void onResourceManagerReload( @Nonnull IResourceManager manager, Predicate<IResourceType> predicate )
        {
            ResourceMount mount = ref.get();
            if( mount != null ) mount.load();
        }
    }
}
