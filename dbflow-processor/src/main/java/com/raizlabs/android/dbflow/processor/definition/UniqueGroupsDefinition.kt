package com.raizlabs.android.dbflow.processor.definition

import com.raizlabs.android.dbflow.annotation.ConflictAction
import com.raizlabs.android.dbflow.annotation.UniqueGroup
import com.raizlabs.android.dbflow.processor.definition.column.ColumnDefinition
import com.raizlabs.android.dbflow.processor.definition.column.ReferenceColumnDefinition
import com.raizlabs.android.dbflow.sql.QueryBuilder
import com.squareup.javapoet.CodeBlock
import java.util.*

/**
 * Description:
 */
class UniqueGroupsDefinition(
    var number: Int,
    private val uniqueConflict: ConflictAction
) {
    /** Convenience for the KAPT path which has a real annotation instance. */
    constructor(uniqueGroup: UniqueGroup) : this(uniqueGroup.groupNumber, uniqueGroup.uniqueConflict)

    var columnDefinitionList: MutableList<ColumnDefinition> = ArrayList()

    fun addColumnDefinition(columnDefinition: ColumnDefinition) {
        columnDefinitionList.add(columnDefinition)
    }

    val creationName: CodeBlock
        get() {
            val codeBuilder = CodeBlock.builder().add(", UNIQUE(")
            var count = 0
            columnDefinitionList.forEach {
                if (count > 0) {
                    codeBuilder.add(",")
                }
                if (it is ReferenceColumnDefinition) {
                    for (reference in it._referenceDefinitionList) {
                        codeBuilder.add(QueryBuilder.quote(reference.columnName))
                    }
                } else {
                    codeBuilder.add(QueryBuilder.quote(it.columnName))
                }
                count++
            }
            codeBuilder.add(") ON CONFLICT \$L", uniqueConflict)
            return codeBuilder.build()
        }
}