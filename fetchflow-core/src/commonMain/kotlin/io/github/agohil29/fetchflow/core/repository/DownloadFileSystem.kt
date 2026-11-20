package io.github.agohil29.fetchflow.core.repository

import io.github.agohil29.fetchflow.core.model.ConflictStrategy
import okio.Path
import okio.Sink
import okio.Source

interface DownloadFileSystem {
    fun exists(path: Path): Boolean
    fun size(path: Path): Long
    fun createDirectories(dir: Path)
    fun sink(path: Path, append: Boolean = false): Sink
    fun source(path: Path): Source
    fun atomicMove(source: Path, destination: Path)
    fun delete(path: Path)
    fun resolveUniquePath(directory: Path, baseName: String, conflictStrategy: ConflictStrategy): Path
    fun sanitizeFileName(candidateName: String): String
}