/*
 * Copyright (c) 2018 Zhang Hai <Dreaming.in.Code.ZH@Gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filesystem;

import android.system.OsConstants;

import org.threeten.bp.Instant;

import java.io.FileDescriptor;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import me.zhanghai.android.files.R;
import me.zhanghai.android.files.functional.compat.LongConsumer;
import me.zhanghai.android.files.provider.common.PosixFileMode;
import me.zhanghai.android.files.provider.common.PosixFileModeBit;
import me.zhanghai.android.files.provider.linux.syscall.Constants;
import me.zhanghai.android.files.provider.linux.syscall.StructGroup;
import me.zhanghai.android.files.provider.linux.syscall.StructPasswd;
import me.zhanghai.android.files.provider.linux.syscall.StructStat;
import me.zhanghai.android.files.provider.linux.syscall.StructTimespec;
import me.zhanghai.android.files.provider.linux.syscall.SyscallException;
import me.zhanghai.android.files.provider.linux.syscall.Syscalls;
import me.zhanghai.android.files.util.ExceptionUtils;
import me.zhanghai.android.files.util.MoreTextUtils;

public class Syscall {

    @NonNull
    public static LocalFileSystem.Information getInformation(@NonNull String path)
            throws FileSystemException {
        StructStat stat;
        try {
            stat = Syscalls.lstat(path);
        } catch (SyscallException e) {
            throw new FileSystemException(R.string.file_error_information, e);
        }
        String symbolicLinkTarget = null;
        boolean isSymbolicLinkStat = false;
        boolean isSymbolicLink = OsConstants.S_ISLNK(stat.st_mode);
        if (isSymbolicLink) {
            try {
                symbolicLinkTarget = Syscalls.readlink(path);
            } catch (SyscallException e) {
                throw new FileSystemException(R.string.file_error_information, e);
            }
            try {
                stat = Syscalls.stat(path);
                isSymbolicLinkStat = true;
            } catch (SyscallException e) {
                e.printStackTrace();
            }
        }
        PosixFileType type = parseType(stat.st_mode);
        EnumSet<PosixFileModeBit> mode = PosixFileMode.fromInt(stat.st_mode);
        PosixUser owner = new PosixUser();
        owner.id = stat.st_uid;
        PosixGroup group = new PosixGroup();
        group.id = stat.st_gid;
        long size = stat.st_size;
        Instant lastModificationTime = Instant.ofEpochSecond(stat.st_mtim.tv_sec,
                stat.st_mtim.tv_nsec);
        try {
            StructPasswd passwd = Syscalls.getpwuid(owner.id);
            if (passwd != null) {
                owner.name = passwd.pw_name;
            }
        } catch (SyscallException e) {
            // It's valid to have a file with a non-existent owner.
            e.printStackTrace();
        }
        try {
            StructGroup structGroup = Syscalls.getgrgid(group.id);
            if (structGroup != null) {
                group.name = structGroup.gr_name;
            }
        } catch (SyscallException e) {
            // It's valid to have a file with a non-existent group.
            e.printStackTrace();
        }
        return new LocalFileSystem.Information(isSymbolicLinkStat, type, mode, owner, group, size,
                lastModificationTime, isSymbolicLink, symbolicLinkTarget);
    }

    @NonNull
    static PosixFileType parseType(@NonNull int st_mode) {
        return OsConstants.S_ISDIR(st_mode) ? PosixFileType.DIRECTORY
                : OsConstants.S_ISCHR(st_mode) ? PosixFileType.CHARACTER_DEVICE
                : OsConstants.S_ISBLK(st_mode) ? PosixFileType.BLOCK_DEVICE
                : OsConstants.S_ISREG(st_mode) ? PosixFileType.REGULAR_FILE
                : OsConstants.S_ISFIFO(st_mode) ? PosixFileType.FIFO
                : OsConstants.S_ISLNK(st_mode) ? PosixFileType.SYMBOLIC_LINK
                : OsConstants.S_ISSOCK(st_mode) ? PosixFileType.SOCKET
                : PosixFileType.UNKNOWN;
    }

    public static void copy(@NonNull String fromPath, @NonNull String toPath, boolean overwrite,
                            long notifyByteCount, @Nullable LongConsumer listener)
            throws FileSystemException, InterruptedException {
        copy(fromPath, toPath, false, overwrite, notifyByteCount, listener);
    }

    /*
     * @see android.os.FileUtils#copy(java.io.FileDescriptor, java.io.FileDescriptor,
     *      android.os.FileUtils.ProgressListener, android.os.CancellationSignal, long)
     * @see https://github.com/gnome/glib/blob/master/gio/gfile.c file_copy_fallback()
     */
    private static void copy(@NonNull String fromPath, @NonNull String toPath, boolean forMove,
                             boolean overwrite, long notifyByteCount,
                             @Nullable LongConsumer listener) throws FileSystemException,
            InterruptedException {
        StructStat fromStat;
        try {
            fromStat = Syscalls.lstat(fromPath);
            if (OsConstants.S_ISREG(fromStat.st_mode)) {
                FileDescriptor fromFd = Syscalls.open(fromPath, OsConstants.O_RDONLY, 0);
                try {
                    FileDescriptor toFd = createFile(toPath, overwrite, fromStat.st_mode);
                    try {
                        long copiedByteCount = 0;
                        long unnotifiedByteCount = 0;
                        try {
                            long sentByteCount;
                            while ((sentByteCount = Syscalls.sendfile(toFd, fromFd, null,
                                    notifyByteCount)) != 0) {
                                copiedByteCount += sentByteCount;
                                unnotifiedByteCount += sentByteCount;
                                if (unnotifiedByteCount >= notifyByteCount) {
                                    if (listener != null) {
                                        listener.accept(copiedByteCount);
                                    }
                                    unnotifiedByteCount = 0;
                                }
                                ExceptionUtils.throwIfInterrupted();
                            }
                        } finally {
                            if (unnotifiedByteCount > 0 && listener != null) {
                                listener.accept(copiedByteCount);
                            }
                        }
                    } finally {
                        Syscalls.close(toFd);
                    }
                } finally {
                    Syscalls.close(fromFd);
                }
            } else if (OsConstants.S_ISDIR(fromStat.st_mode)) {
                try {
                    Syscalls.mkdir(toPath, fromStat.st_mode);
                } catch (SyscallException e) {
                    if (overwrite && e.getErrno() == OsConstants.EEXIST) {
                        try {
                            StructStat toStat = Syscalls.lstat(toPath);
                            if (!OsConstants.S_ISDIR(toStat.st_mode)) {
                                Syscalls.remove(toPath);
                                Syscalls.mkdir(toPath, fromStat.st_mode);
                            }
                        } catch (SyscallException e2) {
                            e2.addSuppressed(e);
                            throw e2;
                        }
                    }
                }
            } else if (OsConstants.S_ISLNK(fromStat.st_mode)) {
                String target = Syscalls.readlink(fromPath);
                try {
                    Syscalls.symlink(target, toPath);
                } catch (SyscallException e) {
                    if (overwrite && e.getErrno() == OsConstants.EEXIST) {
                        try {
                            StructStat toStat = Syscalls.lstat(toPath);
                            if (OsConstants.S_ISDIR(toStat.st_mode)) {
                                throw new SyscallException("symlink", OsConstants.EISDIR);
                            }
                            Syscalls.remove(toPath);
                            Syscalls.symlink(target, toPath);
                        } catch (SyscallException e2) {
                            e2.addSuppressed(e);
                            throw e2;
                        }
                    }
                    throw e;
                }
            } else {
                throw new FileSystemException(R.string.file_copy_error_special_file);
            }
        } catch (SyscallException e) {
            throw new FileSystemException(R.string.file_copy_error, e);
        }
        // We don't take error when copying attribute fatal, so errors will only be logged from now
        // on.
        // Ownership should be copied before permissions so that special permission bits like
        // setuid work properly.
        try {
            if (forMove) {
                Syscalls.lchown(toPath, fromStat.st_uid, fromStat.st_gid);
            }
        } catch (SyscallException e) {
            e.printStackTrace();
        }
        try {
            if (!OsConstants.S_ISLNK(fromStat.st_mode)) {
                Syscalls.chmod(toPath, fromStat.st_mode);
            }
        } catch (SyscallException e) {
            e.printStackTrace();
        }
        try {
            StructTimespec[] times = {
                    forMove ? fromStat.st_atim : new StructTimespec(0, Constants.UTIME_OMIT),
                    fromStat.st_mtim
            };
            Syscalls.lutimens(toPath, times);
        } catch (SyscallException e) {
            e.printStackTrace();
        }
        try {
            // TODO: Allow u+rw temporarily if we are to copy xattrs.
            String[] xattrNames = Syscalls.llistxattr(fromPath);
            for (String xattrName : xattrNames) {
                if (!(forMove || MoreTextUtils.startsWith(xattrName, "user."))) {
                    continue;
                }
                byte[] xattrValue = Syscalls.lgetxattr(fromPath, xattrName);
                Syscalls.lsetxattr(fromPath, xattrName, xattrValue, 0);
            }
        } catch (SyscallException e) {
            e.printStackTrace();
        }
        // TODO: SELinux?
    }

    public static void createFile(@NonNull String path) throws FileSystemException {
        try {
            FileDescriptor fd = createFile(path, false, OsConstants.S_IRUSR | OsConstants.S_IWUSR
                    | OsConstants.S_IRGRP | OsConstants.S_IWGRP | OsConstants.S_IROTH
                    | OsConstants.S_IWOTH);
            Syscalls.close(fd);
        } catch (SyscallException e) {
            throw new FileSystemException(R.string.file_create_file_error, e);
        }
    }

    private static FileDescriptor createFile(@NonNull String path, boolean overwrite, int mode)
            throws SyscallException {
        int flags = OsConstants.O_WRONLY | OsConstants.O_CREAT | OsConstants.O_TRUNC;
        if (!overwrite) {
            flags |= OsConstants.O_EXCL;
        }
        return Syscalls.open(path, flags, mode);
    }

    public static void createDirectory(@NonNull String path) throws FileSystemException {
        try {
            Syscalls.mkdir(path, OsConstants.S_IRWXU | OsConstants.S_IRWXG | OsConstants.S_IRWXO);
        } catch (SyscallException e) {
            throw new FileSystemException(R.string.file_create_directory_error, e);
        }
    }

    public static void delete(@NonNull String path) throws FileSystemException {
        try {
            Syscalls.remove(path);
        } catch (SyscallException e) {
            throw new FileSystemException(R.string.file_delete_error, e);
        }
    }

    public static List<String> getChildren(@NonNull String path) throws FileSystemException {
        String[] children;
        try {
            children = Syscalls.listdir(path);
        } catch (SyscallException e) {
            throw new FileSystemException(e);
        }
        return Arrays.asList(children);
    }

    public static void move(@NonNull String fromPath, @NonNull String toPath, boolean overwrite,
                            long notifyByteCount, @Nullable LongConsumer listener)
            throws FileSystemException, InterruptedException {
        try {
            rename(fromPath, toPath, overwrite);
        } catch (SyscallException e) {
            copy(fromPath, toPath, true, overwrite, notifyByteCount, listener);
            delete(fromPath);
        }
    }

    public static void rename(@NonNull String fromPath, @NonNull String toPath)
            throws FileSystemException {
        try {
            rename(fromPath, toPath, false);
        } catch (SyscallException e) {
            throw new FileSystemException(R.string.file_rename_error, e);
        }
    }

    private static void rename(@NonNull String fromPath, @NonNull String toPath, boolean overwrite)
            throws SyscallException {
        if (!overwrite) {
            try {
                Syscalls.lstat(toPath);
                throw new SyscallException("rename", OsConstants.EEXIST);
            } catch (SyscallException e) {
                if (e.getErrno() != OsConstants.ENOENT) {
                    throw e;
                }
            }
        }
        Syscalls.rename(fromPath, toPath);
    }
}