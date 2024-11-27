package com.github.litian98.renametool2.toolWindow.constant

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.lang.dart.psi.DartComponent
import com.jetbrains.lang.dart.psi.DartVarAccessDeclaration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

object VariableScanner {

    /**
     * 扫描项目中的所有文件，查找带有 @De 注解的变量，并返回它们的名称集合。
     */
    fun findAnnotatedVariables(project: Project): Set<String> {
        val nameSet = ConcurrentHashMap.newKeySet<String>()
        val basePath = project.basePath ?: return emptySet()
        val baseDir = LocalFileSystem.getInstance().findFileByPath(basePath) ?: return emptySet()


        ProgressManager.getInstance().runProcessWithProgressSynchronously({
            val progressIndicator = ProgressManager.getInstance().progressIndicator
            progressIndicator?.isIndeterminate = false

            val files = mutableListOf<VirtualFile>()
            // 一次遍历完成文件收集和计数
            VfsUtilCore.visitChildrenRecursively(baseDir, object : VirtualFileVisitor<Any>() {
                override fun visitFile(file: VirtualFile): Boolean {
                    if (!file.isDirectory && file.extension == "dart") {
                        files.add(file)
                    }
                    return true
                }
            })

            val totalFiles = files.size
            if (totalFiles == 0) return@runProcessWithProgressSynchronously

            val processedFiles = AtomicInteger(0)

            // 并行处理文件
            files.parallelStream().forEach { file ->
                if (progressIndicator?.isCanceled == true) return@forEach

                // 将 PSI 访问包装在读取操作中
                ApplicationManager.getApplication().runReadAction {
                    val psiFile = PsiManager.getInstance(project).findFile(file) ?: return@runReadAction

                    // 直接查找带有 @De 注解的变量
                    PsiTreeUtil.findChildrenOfType(psiFile, DartComponent::class.java).forEach { component ->
                        if (component is DartVarAccessDeclaration) {
                            if (component.metadataList.any { metadata ->
                                    metadata.referenceExpression.textMatches("De")
                                }) {
                                nameSet.add(component.componentName.text)
                            }
                        }
                    }

                    val currentCount = processedFiles.incrementAndGet()
                    progressIndicator?.fraction = currentCount.toDouble() / totalFiles
                    progressIndicator?.text = "正在扫描: ${file.name} ($currentCount/$totalFiles)"
                }
            }

        }, "扫描文件中", true, project)

        return nameSet
    }
}
