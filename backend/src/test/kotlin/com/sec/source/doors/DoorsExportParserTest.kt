package com.sec.source.doors

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DoorsExportParserTest {

    private val moduleUrl = "doors://d:9601/?version=2&prodID=0&urn=urn:telelogic::1-0-M-mod1"

    private fun module(contents: String, version: String = "current", url: String = moduleUrl): String = """
        {
          "__objectId": "mod1",
          "__name": "Test module",
          "__version": "$version",
          "description": "",
          "moduleFullPath": "/T/Test module",
          "url": "$url",
          "__contents": [$contents]
        }
    """.trimIndent()

    private fun heading(num: String, level: String, id: String = "H-$num", url: String? = null): String = """
        {
          "id": "$id", "objectNumber": "$num", "objectLevel": "$level",
          "__moduleUrl": "$moduleUrl",
          "Object Heading": "Heading $num", "Object Text": "", "Object Short Text": "",
          "Object Type": "Heading", "Absolute Number": "$num",
          "__objectUrl": "${url ?: "$moduleUrl-obj-$num"}",
          "__tableObject": "false", "__tableID": "", "__tableURL": "",
          "__tableRowIndex": "", "__tableColumnIndex": "",
          "__outputLinks": [], "__inputLinks": []
        }
    """.trimIndent()

    // -- happy path ----------------------------------------------------------------------------

    @Test
    fun `a minimal export parses into a module and its objects`() {
        val export = DoorsExportParser.parse(module(heading("1", "1")).toByteArray()).getOrThrow()

        assertEquals(moduleUrl, export.moduleId)
        assertEquals("Test module", export.moduleName)
        assertEquals("current", export.moduleVersion)
        assertEquals(1, export.objects.size)

        val obj = export.objects.single()
        assertEquals("$moduleUrl-obj-1", obj.objectUrl)
        assertEquals("Heading 1", obj.props["__name"])
        assertEquals("current", obj.props["__version"])
        assertEquals("000001", obj.props["__sortKey"])
        assertTrue(DoorsLabel.HEADING in obj.labels)
        assertTrue("SEItem" in obj.labels)
    }

    @Test
    fun `the checksum is stable for identical bytes and changes when the file does`() {
        val bytes = module(heading("1", "1")).toByteArray()
        val first = DoorsExportParser.parse(bytes).getOrThrow().checksum
        val second = DoorsExportParser.parse(bytes).getOrThrow().checksum
        assertEquals(first, second)

        val different = DoorsExportParser.parse(module(heading("2", "1")).toByteArray()).getOrThrow().checksum
        assertTrue(first != different)
    }

    @Test
    fun `an object with no __objectUrl is silently excluded, matching importer_py`() {
        val noUrl = heading("1", "1", url = "")
        val export = DoorsExportParser.parse(module(noUrl).toByteArray()).getOrThrow()
        assertTrue(export.objects.isEmpty())
    }

    // -- structural failures ---------------------------------------------------------------------

    @Test
    fun `bytes that are not JSON fail with NotJson`() {
        val result = DoorsExportParser.parse("not json".toByteArray())
        assertTrue(result.isFailure)
        val problem = (result.exceptionOrNull() as DoorsExportFailure).problem
        assertTrue(problem is DoorsExportProblem.NotJson)
    }

    @Test
    fun `a JSON array at the top level fails with NotAnExport`() {
        val result = DoorsExportParser.parse("[]".toByteArray())
        val problem = (result.exceptionOrNull() as DoorsExportFailure).problem
        assertTrue(problem is DoorsExportProblem.NotAnExport)
    }

    @Test
    fun `a missing required module key fails with Invalid`() {
        val text = """{"__name":"x","__version":"current","url":"$moduleUrl","__contents":[]}"""
        val result = DoorsExportParser.parse(text.toByteArray())
        val problem = (result.exceptionOrNull() as DoorsExportFailure).problem
        assertTrue(problem is DoorsExportProblem.Invalid)
        assertTrue(problem.detail.contains("__objectId"))
    }

    @Test
    fun `a current module url with a non-current version fails with Invalid`() {
        val result = DoorsExportParser.parse(module(heading("1", "1"), version = "4.0").toByteArray())
        val problem = (result.exceptionOrNull() as DoorsExportFailure).problem
        assertTrue(problem is DoorsExportProblem.Invalid)
    }

    @Test
    fun `a baseline module url whose version disagrees fails with Invalid`() {
        val baselineUrl = "doors://d:9601/?version=2&prodID=0&urn=urn:telelogic::1-0-B-mod1-4.0"
        val result = DoorsExportParser.parse(
            module(heading("1", "1"), version = "5.0", url = baselineUrl).toByteArray(),
        )
        val problem = (result.exceptionOrNull() as DoorsExportFailure).problem
        assertTrue(problem is DoorsExportProblem.Invalid)
    }

    // -- warnings, never a failure -------------------------------------------------------------

    @Test
    fun `an object whose __moduleUrl disagrees with the module's own is a warning, not a failure`() {
        // Targets only the __moduleUrl field's value, leaving __objectUrl (a different substring)
        // untouched, so the object is still included and only the mismatch is what is asserted.
        val mismatched = heading("1", "1").replace(
            """"__moduleUrl": "$moduleUrl"""",
            """"__moduleUrl": "doors://other"""",
        )
        val export = DoorsExportParser.parse(module(mismatched).toByteArray()).getOrThrow()
        assertTrue(export.warnings.any { "__moduleUrl" in it })
    }

    @Test
    fun `a duplicate JSON key is reported as a warning, not a failure`() {
        val text = """
            {
              "__objectId": "mod1", "__name": "Test module", "__name": "Second name",
              "__version": "current", "url": "$moduleUrl", "__contents": []
            }
        """.trimIndent()
        val export = DoorsExportParser.parse(text.toByteArray()).getOrThrow()
        assertTrue(export.warnings.any { "duplicate" in it.lowercase() && "__name" in it })
        // kotlinx.serialization keeps the *last* value for a repeated key (ADR 0019 §6).
        assertEquals("Second name", export.moduleName)
    }

    @Test
    fun `a nested duplicate key, inside an object's own attributes, is also caught`() {
        val withDuplicateAttr = heading("1", "1").replaceFirst(
            """"Object Type": "Heading",""",
            """"Object Type": "Heading", "Object Type": "Requirement",""",
        )
        val export = DoorsExportParser.parse(module(withDuplicateAttr).toByteArray()).getOrThrow()
        assertTrue(export.warnings.any { "Object Type" in it })
    }

    @Test
    fun `a module at or above 12000 objects warns about probable truncation`() {
        val many = (1..12_000).joinToString(",") { heading(it.toString(), "1") }
        val export = DoorsExportParser.parse(module(many).toByteArray()).getOrThrow()
        assertTrue(export.warnings.any { "truncated" in it })
    }

    @Test
    fun `no truncation warning below the threshold`() {
        val export = DoorsExportParser.parse(module(heading("1", "1")).toByteArray()).getOrThrow()
        assertFalse(export.warnings.any { "truncated" in it })
    }
}
