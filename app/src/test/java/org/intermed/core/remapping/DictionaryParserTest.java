package org.intermed.core.remapping;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DictionaryParserTest {

    @Test
    void parsesTwoNamespaceTinyThroughMojangClientMappings() throws Exception {
        Path dir = Files.createTempDirectory("intermed-dictionary-parser");
        Path tiny = dir.resolve("mappings.tiny");
        Files.writeString(
            tiny,
            "tiny\t2\t0\tofficial\tintermediary\n"
                + "c\tdsj\tnet/minecraft/class_7151\n"
                + "\tm\t()V\ta\tmethod_123\n",
            StandardCharsets.UTF_8
        );
        Path clientTxt = dir.resolve("client.txt");
        Files.writeString(
            clientTxt,
            "net.minecraft.world.level.levelgen.structure.Structure -> dsj:\n",
            StandardCharsets.UTF_8
        );

        MappingDictionary dictionary = new MappingDictionary();
        DictionaryParser.parse(tiny, clientTxt, dictionary);

        assertEquals(
            "net/minecraft/world/level/levelgen/structure/Structure",
            dictionary.map("net/minecraft/class_7151")
        );
        assertEquals("m_123_", dictionary.mapMethodName("net/minecraft/class_7151", "method_123", "()V"));
    }

    @Test
    void parsesNamedTinyMembersWhenAvailable() throws Exception {
        Path dir = Files.createTempDirectory("intermed-dictionary-parser-named");
        Path tiny = dir.resolve("mappings.tiny");
        Files.writeString(
            tiny,
            "tiny\t2\t0\tofficial\tintermediary\tnamed\n"
                + "c\ta\tnet/minecraft/class_42\tnet/minecraft/class_42\n"
                + "\tm\t()V\tm_1000_\tmethod_1000\ttick\n"
                + "\tf\tI\tf_500\tfield_500\tcount\n",
            StandardCharsets.UTF_8
        );
        Path clientTxt = dir.resolve("client.txt");
        Files.writeString(clientTxt, "net.minecraft.server.level.ServerPlayer -> a:\n", StandardCharsets.UTF_8);

        MappingDictionary dictionary = new MappingDictionary();
        DictionaryParser.parse(tiny, clientTxt, dictionary);

        assertEquals("net/minecraft/server/level/ServerPlayer", dictionary.map("net/minecraft/class_42"));
        assertEquals("tick", dictionary.mapMethodName("net/minecraft/class_42", "method_1000", "()V"));
        assertEquals("count", dictionary.mapFieldName("net/minecraft/class_42", "field_500", "I"));
    }

    @Test
    void fusesTinyOfficialMembersThroughTsrgToRuntimeSrgNames() throws Exception {
        Path dir = Files.createTempDirectory("intermed-dictionary-parser-tsrg");
        Path tiny = dir.resolve("mappings.tiny");
        Files.writeString(
            tiny,
            "tiny\t2\t0\tofficial\tintermediary\n"
                + "c\tjb\tnet/minecraft/class_7923\n"
                + "\tf\tLhr;\tai\tfield_41162\n"
                + "\tm\t()V\ta\tmethod_45100\n",
            StandardCharsets.UTF_8
        );
        Path clientTxt = dir.resolve("client.txt");
        Files.writeString(
            clientTxt,
            "net.minecraft.core.registries.BuiltInRegistries -> jb:\n"
                + "    net.minecraft.core.Registry STRUCTURE_POOL_ELEMENT -> ai\n",
            StandardCharsets.UTF_8
        );
        Path joinedTsrg = dir.resolve("joined.tsrg");
        Files.writeString(
            joinedTsrg,
            "tsrg2 obf srg id\n"
                + "jb net/minecraft/src/C_256712_ 256712\n"
                + "\tai f_256846_ 256846\n"
                + "\ta ()V m_257498_ 257498\n",
            StandardCharsets.UTF_8
        );

        MappingDictionary dictionary = new MappingDictionary();
        DictionaryParser.parse(tiny, clientTxt, joinedTsrg, dictionary);

        assertEquals(
            "net/minecraft/core/registries/BuiltInRegistries",
            dictionary.map("net/minecraft/class_7923")
        );
        assertEquals("f_256846_", dictionary.mapFieldName("net/minecraft/class_7923", "field_41162", "Lhr;"));
        assertEquals(
            "f_256846_",
            dictionary.mapFieldName(
                "net/minecraft/core/registries/BuiltInRegistries",
                "field_41162",
                "Lnet/minecraft/core/Registry;"
            )
        );
        assertEquals("m_257498_", dictionary.mapMethodName("net/minecraft/class_7923", "method_45100", "()V"));
    }

    @Test
    void mapsRuntimeOwnerMethodWhenDescriptorWasAlreadyMappedByAsm() throws Exception {
        Path dir = Files.createTempDirectory("intermed-dictionary-parser-runtime-desc");
        Path tiny = dir.resolve("mappings.tiny");
        Files.writeString(
            tiny,
            "tiny\t2\t0\tofficial\tintermediary\tnamed\n"
                + "c\thr\tnet/minecraft/class_2378\tnet/minecraft/core/Registry\n"
                + "\tm\t()Lacp;\tc\tmethod_30517\tkey\n"
                + "c\tacp\tnet/minecraft/class_5321\tnet/minecraft/resources/ResourceKey\n",
            StandardCharsets.UTF_8
        );
        Path clientTxt = dir.resolve("client.txt");
        Files.writeString(
            clientTxt,
            "net.minecraft.core.Registry -> hr:\n"
                + "net.minecraft.resources.ResourceKey -> acp:\n",
            StandardCharsets.UTF_8
        );
        Path joinedTsrg = dir.resolve("joined.tsrg");
        Files.writeString(
            joinedTsrg,
            "tsrg2 obf srg id\n"
                + "hr net/minecraft/src/C_4705_ 4705\n"
                + "\tc ()Lacp; m_123023_ 123023\n"
                + "acp net/minecraft/src/C_1351_ 1351\n",
            StandardCharsets.UTF_8
        );

        MappingDictionary dictionary = new MappingDictionary();
        DictionaryParser.parse(tiny, clientTxt, joinedTsrg, dictionary);

        assertEquals(
            "m_123023_",
            dictionary.mapMethodName(
                "net/minecraft/core/Registry",
                "method_30517",
                "()Lnet/minecraft/resources/ResourceKey;"
            )
        );
    }

    @Test
    void resolveTsrgPathFallsBackToAutoDetectedVersionedMappingsWhenPrimaryClientTxtIsVersionless() throws Exception {
        Path dir = Files.createTempDirectory("intermed-dictionary-tsrg-fallback");
        Path tiny = dir.resolve("mappings.tiny");
        Files.writeString(
            tiny,
            "tiny\t2\t0\tofficial\tintermediary\n"
                + "c\ta\tnet/minecraft/class_1\n",
            StandardCharsets.UTF_8
        );

        Path primaryClient = dir.resolve("client.txt");
        Files.writeString(primaryClient, "net.minecraft.Example -> a:\n", StandardCharsets.UTF_8);

        Path autoClient = dir.resolve("client-1.20.1-20230612.114412-mappings.txt");
        Files.writeString(autoClient, "net.minecraft.Example -> a:\n", StandardCharsets.UTF_8);
        Path joinedTsrg = dir.resolve("joined.tsrg");
        Files.writeString(joinedTsrg, "tsrg2 obf srg id\na net/minecraft/src/C_1_ 1\n", StandardCharsets.UTF_8);

        Path resolved = DictionaryParser.resolveTsrgPath(tiny, primaryClient, autoClient);

        assertEquals(joinedTsrg, resolved);
        assertTrue(Files.exists(resolved));
    }
}
