/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.backend.konan.library.impl

import org.jetbrains.kotlin.backend.konan.library.KonanLibrary
import org.jetbrains.kotlin.backend.konan.library.KonanLibraryReader
import org.jetbrains.kotlin.backend.konan.library.MetadataReader
import org.jetbrains.kotlin.backend.konan.serialization.deserializeModule
import org.jetbrains.kotlin.backend.konan.util.*
import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.konan.target.KonanTarget

class ZippedKonanLibrary(val klibFile: File, override val target: KonanTarget? = null): KonanLibrary {
    init {
        if (!klibFile.exists) {
            error("Could not find $klibFile.")
        }
        if (!klibFile.isFile) {
            error("Expected $klibFile to be a regular file.")
        }
    }

    override val libraryName = klibFile.path.removeSuffixIfPresent(".klib")
    //override val libDir = File(klibFile.zipPath("/"))
    override val libDir = File("/") // doesn't seem to be right. it is not in the default file system.

    // TODO: we don't actually need to extract all of them.
    override val manifestFile: File by lazy {
        extract(super.manifestFile)
    }

    override val resourcesDir: File by lazy {
        extractDir(super.resourcesDir)
    }

    override val kotlinDir: File by lazy {
        extractDir(super.kotlinDir)
    }

    override val nativeDir: File by lazy {
        extractDir(super.nativeDir)
    }

    override val linkdataDir: File by lazy {
        extractDir(super.linkdataDir)
    }

    fun extract(file: File): File {
        val temporary = File.createTempFile(file.name)
        temporary.deleteOnExit()
        klibFile.unzipSingleFileTo(file.path, temporary.path)
        return temporary
    }

    fun extractDir(directory: File): File {
        // TODO: Mark all recursively extracted as deleteOnExit.
        val temporary = File.createTempDir(directory.name)
        klibFile.unzipDirectoryRecursivelyTo(directory.path, temporary.path)
        return temporary
    }

    fun unpackTo(newDir: File) {
        if (newDir.exists) {
            if (newDir.isDirectory) 
                newDir.deleteRecursively()
            else 
                newDir.delete()
        }
        klibFile.unzipAs(newDir)
        if (!newDir.exists) error("Could not unpack $klibFile as $newDir.")
    }
}

class UnzippedKonanLibrary(override val libDir: File, override val target: KonanTarget? = null): KonanLibrary {
    override val libraryName = libDir.path
}

fun KonanLibrary(klib: File, target: KonanTarget? = null) = 
    if (klib.isFile) ZippedKonanLibrary(klib, target) 
    else UnzippedKonanLibrary(klib, target)

abstract class FileBasedLibraryReader(
    val currentAbiVersion: Int,
    val reader: MetadataReader): KonanLibraryReader {
    val moduleHeaderData: ByteArray by lazy {
        reader.loadSerializedModule()
    }

    fun packageMetadata(fqName: String): ByteArray =
        reader.loadSerializedPackageFragment(fqName)

    override fun moduleDescriptor(specifics: LanguageVersionSettings) 
        = deserializeModule(specifics, {packageMetadata(it)}, 
            moduleHeaderData)
}

class LibraryReaderImpl(library: KonanLibrary, currentAbiVersion: Int) : 
            FileBasedLibraryReader(currentAbiVersion, MetadataReaderImpl(library)), 
            KonanLibrary by library  {

    val manifestProperties: Properties by lazy {
        manifestFile.loadProperties()
    }

    val abiVersion: String
        get() {
            val manifestAbiVersion = manifestProperties.getProperty("abi_version")
            if ("$currentAbiVersion" != manifestAbiVersion) 
                error("ABI version mismatch. Compiler expects: $currentAbiVersion, the library is $manifestAbiVersion")
            return manifestAbiVersion
        }

    override val bitcodePaths: List<String>
        get() = (kotlinDir.listFiles + nativeDir.listFiles).map{it.absolutePath}

    override val linkerOpts: List<String>
        get() = manifestProperties.propertyList("linkerOpts", target!!.targetSuffix)
}

