package com.github.litian98.renametool2.toolWindow

import com.github.litian98.renametool2.toolWindow.constant.NewNameGenerator
import com.github.litian98.renametool2.toolWindow.constant.RenameProcessor
import com.github.litian98.renametool2.toolWindow.constant.VariableScanner
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.vfs.VirtualFileManager


object RenameAction : AnAction() {

    /**
     * 这是一个重命名的按钮,会扫描所有的文件,并且把所有的变量名找出来
     * 然后根据这些变量名,以及词库,生成一个对应的映射表
     * 注意点,生成对应表的时候如果有重复元素,需要添加一个数字 1,如果还有重复,则添加数字 2,以此类推
     * 然后根据这个映射表,进行重命名
     *
     */
    override fun actionPerformed(e: AnActionEvent) {
        // 获取项目、虚拟文件和编辑器
        val project = e.project ?: return

        //扫描出所有的变量名
        val nameSet = VariableScanner.findAnnotatedVariables(project)

        //重建映射表
        val map = NewNameGenerator.generateNewNameMap(project, nameSet)

        //开始重命名
        RenameProcessor.doRename(project, map)

        //刷新文件系统,让idea 能识别新生成的文件
        VirtualFileManager.getInstance().syncRefresh()
    }


}