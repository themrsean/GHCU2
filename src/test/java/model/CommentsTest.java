package model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for model.Comments and nested types.
 *
 * Verified targets (from src/main/java/model/Comments.java):
 * - public static List<ParsedComment> parseInjectedComments(String markdown)
 * - public static class CommentDef { public String toString(); ... }
 * - public static class CommentsLibrary { public static CommentsLibrary newEmpty(); }
 * - public static class CommentsStore { public CommentsLibrary load(Path); public void save(Path, CommentsLibrary); }
 */
final class CommentsTest {

    @Test
    void parseInjectedComments_nullOrEmpty_returnsEmptyList() {
        List<Comments.ParsedComment> a = Comments.parseInjectedComments(null);
        assertNotNull(a);
        assertTrue(a.isEmpty());

        List<Comments.ParsedComment> b = Comments.parseInjectedComments("");
        assertNotNull(b);
        assertTrue(b.isEmpty());
    }

    @Test
    void parseInjectedComments_parsesSingleWellFormedComment() {
        String md = "<a id=\"cmt_1\"></a>\n" +
                "> #### Missing semicolon\n" +
                "> * -5 points (ri_style)\n";

        List<Comments.ParsedComment> parsed = Comments.parseInjectedComments(md);
        assertEquals(1, parsed.size());

        Comments.ParsedComment p = parsed.get(0);
        assertEquals("cmt_1", p.anchorId());
        assertEquals("ri_style", p.rubricItemId());
        assertEquals(5, p.pointsLost());
        assertEquals("Missing semicolon", p.title());
    }

    @Test
    void parseInjectedComments_multipleCommentsAndResetBehavior() {
        String md = "<a id=\"c1\"></a>\n" +
                "> #### First\n" +
                "> * -1 points (r1)\n" +
                "Some intervening text\n" +
                "<a id=\"c2\"></a>\n" +
                "> #### Second\n" +
                "> * -2 points (r2)\n";

        List<Comments.ParsedComment> parsed = Comments.parseInjectedComments(md);
        assertEquals(2, parsed.size());

        Comments.ParsedComment p1 = parsed.get(0);
        assertEquals("c1", p1.anchorId());
        assertEquals("r1", p1.rubricItemId());
        assertEquals(1, p1.pointsLost());
        assertEquals("First", p1.title());

        Comments.ParsedComment p2 = parsed.get(1);
        assertEquals("c2", p2.anchorId());
        assertEquals("r2", p2.rubricItemId());
        assertEquals(2, p2.pointsLost());
        assertEquals("Second", p2.title());
    }

    @Test
    void parseInjectedComments_ignoresMalformedPoints() {
        String md = "<a id=\"c_bad\"></a>\n" +
                "> #### Broken\n" +
                "> * -X points (rX)\n"; // non-numeric between '-' and 'points'

        List<Comments.ParsedComment> parsed = Comments.parseInjectedComments(md);
        // Should not produce a ParsedComment because points parsing failed
        assertTrue(parsed.isEmpty());
    }

    @Test
    void commentDef_toString_handlesNullsAndValues() {
        Comments.CommentDef d1 = new Comments.CommentDef();
        // both id and title null -> " — "
        assertEquals(" — ", d1.toString());

        Comments.CommentDef d2 = new Comments.CommentDef();
        d2.setCommentId("c123");
        d2.setTitle("Bad naming");
        assertEquals("c123 — Bad naming", d2.toString());
    }

    @Test
    void commentsLibrary_newEmpty_hasSchema1AndEmptyList() {
        Comments.CommentsLibrary lib = Comments.CommentsLibrary.newEmpty();
        assertEquals(1, lib.getSchemaVersion());
        assertNotNull(lib.getComments());
        assertTrue(lib.getComments().isEmpty());
    }

    @Test
    void commentsStore_loadAndSave_and_nullChecks(@TempDir Path tmp) throws Exception {
        Comments.CommentsStore store = new Comments.CommentsStore();

        // load(null) should throw IOException
        IOException ex1 = assertThrows(IOException.class, () -> store.load(null));
        assertTrue(ex1.getMessage().contains("Comments path is null"));

        // save(null, ..) should throw
        Comments.CommentsLibrary lib = Comments.CommentsLibrary.newEmpty();
        IOException ex2 = assertThrows(IOException.class, () -> store.save(null, lib));
        assertTrue(ex2.getMessage().contains("Comments path is null"));

        // save with null file should throw
        Path p = tmp.resolve("comments.json");
        IOException ex3 = assertThrows(IOException.class, () -> store.save(p, null));
        assertTrue(ex3.getMessage().contains("Comments file is null"));

        // save a real library and then load it back
        Comments.CommentDef def = new Comments.CommentDef();
        def.setCommentId("cA");
        def.setAssignmentKey("ASG1");
        def.setRubricItemId("ri_a");
        def.setTitle("Title A");
        def.setBodyMarkdown("Body");
        def.setPointsDeducted(7);

        Comments.CommentsLibrary toSave = Comments.CommentsLibrary.newEmpty();
        toSave.getComments().add(def);

        store.save(p, toSave);

        assertTrue(Files.exists(p));

        Comments.CommentsLibrary loaded = store.load(p);
        assertNotNull(loaded);
        assertEquals(1, loaded.getSchemaVersion());
        assertNotNull(loaded.getComments());
        assertEquals(1, loaded.getComments().size());

        Comments.CommentDef ld = loaded.getComments().get(0);
        assertEquals("cA", ld.getCommentId());
        assertEquals("ASG1", ld.getAssignmentKey());
        assertEquals("ri_a", ld.getRubricItemId());
        assertEquals("Title A", ld.getTitle());
        assertEquals("Body", ld.getBodyMarkdown());
        assertEquals(7, ld.getPointsDeducted());

        // load non-existent path should return an empty library
        Path missing = tmp.resolve("does_not_exist.json");
        Comments.CommentsLibrary empty = store.load(missing);
        assertNotNull(empty);
        assertEquals(1, empty.getSchemaVersion());
        assertNotNull(empty.getComments());
        assertTrue(empty.getComments().isEmpty());
    }
}
