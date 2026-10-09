package ai.moeru.airicraft.agent.tasks;

import org.junit.jupiter.api.Test;
import java.util.Locale;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class CraftingOpportunityNormalizationTest {
    @Test void keepsExistingRecipeIdentifiersIncludingUnicodeAndSeparators() {
        String[] examples={null,"","minecraft:oak_planks","MINECRAFT:Oak_Planks","mod:item/path",
            "___STONE--brick___","İtem","Straße","a\n\tb","世界","🔥item🔥"};
        for(var input:examples)assertEquivalent(input);
        var random=new Random(48312);
        for(int trial=0;trial<10000;trial++){
            var input=new StringBuilder();
            if(random.nextBoolean())input.append("minecraft:");
            for(int n=random.nextInt(50);n>0;n--)input.append((char)random.nextInt(65536));
            assertEquivalent(input.toString());
        }
    }
    private static void assertEquivalent(String input){
        String expected=CraftingOpportunity.displayItemId(input).toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+","_").replaceAll("^_+|_+$","");
        assertEquals(expected,CraftingOpportunity.recipeIdSegment(input));
    }
}
