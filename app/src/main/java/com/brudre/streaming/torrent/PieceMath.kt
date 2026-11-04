package com.brudre.streaming.torrent

/**
 * Conversão entre posição no arquivo de vídeo e peças do torrent.
 * Um torrent é uma sequência contínua de bytes dividida em peças; o arquivo
 * começa em [fileOffset] dentro dessa sequência.
 */
class PieceMath(
    val fileOffset: Long,
    val fileSize: Long,
    val pieceLength: Int,
    val numPieces: Int,
) {
    init {
        require(pieceLength > 0) { "pieceLength inválido" }
        require(fileSize >= 0 && fileOffset >= 0)
    }

    val firstPiece: Int get() = (fileOffset / pieceLength).toInt()
    val lastPiece: Int get() = if (fileSize == 0L) firstPiece else ((fileOffset + fileSize - 1) / pieceLength).toInt().coerceAtMost(numPieces - 1)

    /** Peça que contém o byte [filePosition] do arquivo. */
    fun pieceAt(filePosition: Long): Int {
        val p = filePosition.coerceIn(0, maxOf(fileSize - 1, 0))
        return ((fileOffset + p) / pieceLength).toInt()
    }

    /** Posição (relativa ao arquivo) logo após o fim da peça [piece], limitada ao tamanho do arquivo. */
    fun pieceEndInFile(piece: Int): Long = ((piece + 1L) * pieceLength - fileOffset).coerceIn(0, fileSize)

    /** Peças do arquivo a partir de [fromPiece], no máximo [count]. */
    fun window(fromPiece: Int, count: Int): IntRange {
        val start = fromPiece.coerceIn(firstPiece, lastPiece)
        val end = (start + count - 1).coerceAtMost(lastPiece)
        return start..end
    }

    /** Quantas peças cobrem [bytes] a partir de qualquer posição (para dimensionar o read-ahead). */
    fun piecesFor(bytes: Long): Int = ((bytes + pieceLength - 1) / pieceLength).toInt().coerceAtLeast(1)
}
