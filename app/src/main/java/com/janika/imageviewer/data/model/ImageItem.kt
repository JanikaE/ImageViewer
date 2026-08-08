package com.janika.imageviewer.data.model

/**
 * 图片查看器的图片项 - 支持本地文件和网络文件
 */
data class ImageItem(
    /** 显示路径：本地文件为绝对路径，网络文件为相对路径 */
    val path: String,
    /** 文件名（用于标题显示） */
    val name: String,
    /** 是否为网络文件 */
    val isNetworkFile: Boolean = false,
    /** SMB 服务器地址（仅网络文件） */
    val smbServerAddress: String? = null,
    /** SMB 共享名（仅网络文件） */
    val smbShareName: String? = null
) {
    companion object {
        /** 从本地 ImageFile 列表构建 ImageItem 列表 */
        fun fromLocalFiles(files: List<ImageFile>): List<ImageItem> {
            return files.filter { !it.isDirectory }.map { file ->
                ImageItem(
                    path = file.path,
                    name = file.name,
                    isNetworkFile = false
                )
            }
        }

        /** 从网络 ImageFile 列表构建 ImageItem 列表 */
        fun fromNetworkFiles(
            files: List<ImageFile>,
            serverAddress: String,
            shareName: String
        ): List<ImageItem> {
            return files.filter { !it.isDirectory }.map { file ->
                ImageItem(
                    path = file.path,
                    name = file.name,
                    isNetworkFile = true,
                    smbServerAddress = serverAddress,
                    smbShareName = shareName
                )
            }
        }
    }
}
