package fish.payara.extras.upgrade;

import org.glassfish.api.admin.CommandException;
import org.glassfish.hk2.api.PerLookup;
import org.jvnet.hk2.annotations.Service;

@Deprecated
@Service(name = "_upgrade-nodes")
@PerLookup
public class UpgradeNodesCommandDeprecated extends UpgradeNodesCommand {

    @Override
    protected int executeCommand() throws CommandException {
        return super.executeCommand();
    }
}
