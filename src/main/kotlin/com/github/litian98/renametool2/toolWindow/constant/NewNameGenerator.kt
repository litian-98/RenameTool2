package com.github.litian98.renametool2.toolWindow.constant

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties
import kotlin.math.absoluteValue

object NewNameGenerator {

    /**
     * 读取配置文件中的 channelName。如果配置文件不存在，会提示用户输入并创建配置文件。
     * 如果 channelName 无法获取，则返回 null。
     */
    fun readChannelName(project: Project): String? {
        val projectBasePath = project.basePath ?: return ""
        val configFile = File(projectBasePath, "projectEncrypt.properties")
        val properties = Properties()
        var channelName: String? = null

        if (!configFile.exists()) {
            // 配置文件不存在，需要在 EDT 中弹出对话框
            ApplicationManager.getApplication().invokeAndWait({
                val inputChannelName = Messages.showInputDialog(
                    project,
                    "配置文件 projectEncrypt.properties 不存在。\n请输入 channelName 的值，将创建该配置文件。",
                    "创建配置文件",
                    Messages.getQuestionIcon()
                )

                // 创建配置文件并写入 channelName
                properties.setProperty("channelName", inputChannelName)
                FileOutputStream(configFile).use { properties.store(it, "Project Encryption Configuration") }
                channelName = inputChannelName

            }, ModalityState.any())
        }


        // 尝试从已存在的配置文件中读取 channelName
        FileInputStream(configFile).use { properties.load(it) }
        val newChannelName: String? = properties.getProperty("channelName")

        if (newChannelName.isNullOrBlank()) {
            // channelName 未配置，提示错误
            Messages.showInfoMessage("配置文件中未找到有效的 channelName 。", "错误")
            return null
        }

        // 打印 channelName
        Notifications.create(project, "channelName", newChannelName)
        return newChannelName
    }

    /**
     * 生成新的名称映射表。
     * 先读取配置文件中的 channelName，然后调用 doGenerate 方法生成映射表。
     */
    fun generateNewNameMap(project: Project, nameKey: Set<String>): Map<String, String> {
        val nameMap: Map<String, String> = ReadAction.nonBlocking<Map<String, String>> {
            val channelName = readChannelName(project) ?: return@nonBlocking emptyMap()

            if (channelName.isBlank()) return@nonBlocking emptyMap()

            // 将 channelName 传入 doGenerate 方法
            generateNewNameSet(nameKey, 1, channelName)
        }.executeSynchronously()

        saveMapping(nameMap, project)

        return nameMap
    }

    private fun saveMapping(map: Map<String, String>, project: Project) {
        val configFile = File(project.basePath, "nameMapping.properties")
        Properties().apply {
            putAll(map)
            FileOutputStream(configFile).use { store(it, "Name Mapping") }
        }
    }


    /**
     * 生成新的名称。
     * 使用递归的方式生成新的名称，直到生成不重复的名称。
     */
    private fun generateNewNameSet(oldNameSet: Set<String>, count: Int, channelName: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        // 根据 count 和 channelName 生成新的名称映射。

        for (oldName in oldNameSet) {
            generateNewName(oldName, count, channelName).let { result[oldName] = it }
        }

        return if (result.values.toSet().size != result.size) {
            // 检测到重复，递归调用并增加计数
            generateNewNameSet(oldNameSet, count + 1, channelName)
        } else {
            result
        }
    }

    private fun generateNewName(oldName: String, count: Int, channelName: String): String {
        val seed = "$channelName$count$oldName"
        val nameSet = KeyName.set

        // 根据 seed 计算哈希，并通过绝对值和取模操作得到索引
        fun getNameComponent(type: String) = nameSet.elementAt(
            (seed + type).hashCode().absoluteValue % nameSet.size
        )

        val prefixName = getNameComponent("prefix")
        val middleName = getNameComponent("middle").replaceFirstChar { it.uppercase() }
        val suffixName = getNameComponent("suffix").replaceFirstChar { it.uppercase() }

        return "$prefixName$middleName$suffixName"
    }


}