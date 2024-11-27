package com.github.litian98.renametool2.toolWindow.constant

import com.google.dart.server.GetRefactoringConsumer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.lang.dart.analyzer.DartAnalysisServerService
import com.jetbrains.lang.dart.assists.AssistUtils
import com.jetbrains.lang.dart.psi.DartVarAccessDeclaration
import org.dartlang.analysis.server.protocol.RefactoringFeedback
import org.dartlang.analysis.server.protocol.RefactoringKind
import org.dartlang.analysis.server.protocol.RefactoringProblem
import org.dartlang.analysis.server.protocol.RenameOptions
import org.dartlang.analysis.server.protocol.RequestError
import org.dartlang.analysis.server.protocol.SourceChange
import org.jetbrains.kotlin.psi.psiUtil.endOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

object RenameProcessor {


    private val log = Logger.getInstance(this.javaClass)

    fun doRename(project: Project, map: Map<String, String>) {
        // 遍历 project 中的所有文件，扫描 dart 文件
        val basePath = project.basePath ?: return
        val baseDir = LocalFileSystem.getInstance().findFileByPath(basePath) ?: return

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

            // 串行处理文件
            files.forEach { file ->
                if (progressIndicator?.isCanceled == true) return@forEach

                // 在读操作中收集需要重命名的组件
                val componentsToRename = mutableListOf<Pair<DartVarAccessDeclaration, String>>()

                ApplicationManager.getApplication().runReadAction {
                    val psiFile = PsiManager.getInstance(project).findFile(file)
                    if (psiFile != null) {
                        // 查找带有 @De 注解的变量并收集
                        PsiTreeUtil.findChildrenOfType(psiFile, DartVarAccessDeclaration::class.java)
                            .forEach { component ->
                                if (component.metadataList.any { metadata -> metadata.referenceExpression.textMatches("De") }) {
                                    val newName: String = map.getOrDefault(component.componentName.text, "")
                                    if (newName != "") {
                                        componentsToRename.add(component to newName)
                                    }
                                }
                            }
                    }
                }

                // 在读操作外调用重命名方法，避免在读操作内启动写操作
                componentsToRename.forEach { (component, newName) ->
                    rename(project, file, component, newName)
                }

                val currentCount = processedFiles.incrementAndGet()
                progressIndicator?.fraction = currentCount.toDouble() / totalFiles
                progressIndicator?.text = "正在重构: ${file.name} ($currentCount/$totalFiles)"
            }
        }, "重构文件中", true, project)
    }

    fun rename(
        project: Project,
        virtualFile: VirtualFile,
        dartVar: DartVarAccessDeclaration,
        newName: String
    ) {
        // 获取重构变更和潜在编辑
        val (change, potentialEdits) = getRefactoring(project, virtualFile, dartVar.endOffset, newName)
        if (change == null) {
            return
        }

        // 应用源代码变更
        WriteCommandAction.runWriteCommandAction(project) {
            // 重命名
            AssistUtils.applySourceChange(project, change, false, potentialEdits)
            //重命名后提交文档
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            // 删除 @De 注解
            dartVar.metadataList.firstOrNull()?.delete()
        }
    }

    // 获取重构变更和潜在编辑
    fun getRefactoring(
        project: Project,
        virtualFile: VirtualFile,
        offset: Int,
        newName: String,
    ): Pair<SourceChange?, Set<String>> {
        // 更新分析服务器中的文件内容
        DartAnalysisServerService.getInstance(project).updateFilesContent()

        // 设置新名字
        val renameOptions = RenameOptions(newName)

        // 用于存储结果的变量
        var result: Pair<SourceChange?, Set<String>>? = null

        // 用于存储异常的变量
        var exception: Throwable? = null

        // 使用 CountDownLatch 等待回调完成
        val latch = CountDownLatch(1)

        // 从分析服务器获取重构所需要修改的信息
        DartAnalysisServerService.getInstance(project).edit_getRefactoring(
            RefactoringKind.RENAME,
            virtualFile,
            offset,
            0,
            false,
            renameOptions,
            object : GetRefactoringConsumer {
                override fun computedRefactorings(
                    initialProblems: List<RefactoringProblem>,
                    optionsProblems: List<RefactoringProblem>,
                    finalProblems: List<RefactoringProblem>,
                    feedback: RefactoringFeedback,
                    change: SourceChange?,
                    potentialEdits: List<String>?
                ) {
                    result = Pair(change, potentialEdits?.toSet() ?: emptySet())
                    latch.countDown()
                }

                override fun onError(requestError: RequestError) {
                    log.info("服务器错误: ${requestError.message}")
                    exception = RuntimeException(requestError.message)
                    latch.countDown()
                }
            }
        )

        // 等待回调执行完成
        latch.await()

        // 如果有异常则抛出
        exception?.let { throw it }

        // 返回结果
        return result!!
    }
}