package java21;

import org.sonatype.nexus.security.authz.WildcardPermission2;

import java.util.List;
import java.util.Set;

public class TestWildcardPermission2 extends WildcardPermission2 {
    public TestWildcardPermission2() {
        super();
    }

    public void setPartsPublic(List<String> part1, List<String> part2, boolean caseSensitive) {
        super.setParts(part1, part2, caseSensitive);
    }

    public List<Set<String>> getPartsPublic() {
        return super.getParts();
    }
}