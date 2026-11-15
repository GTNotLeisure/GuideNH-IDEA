package com.hfstudio.guidenh

import com.google.gson.Gson
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException

private val contentFlavor = DataFlavor("application/vnd.guidenh.itemstack+json;class=java.lang.String")
private val contentGson = Gson()

public open class GuideNhContentTransferable(private val id: String, private val capability: String) : Transferable {
    override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(contentFlavor, DataFlavor.stringFlavor)
    override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == contentFlavor || flavor == DataFlavor.stringFlavor
    override fun getTransferData(flavor: DataFlavor): Any = when (flavor) {
        contentFlavor -> contentGson.toJson(mapOf("id" to id, "capability" to capability, "source" to "picker-entry"))
        DataFlavor.stringFlavor -> id
        else -> throw UnsupportedFlavorException(flavor)
    }
}
