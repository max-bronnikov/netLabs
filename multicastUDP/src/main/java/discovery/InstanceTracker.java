package discovery;

import java.net.InetSocketAddress;
import java.util.*;

class InstanceTracker {

    private final String selfId;

    private static class Info {
        InetSocketAddress addr;
        long lastSeen;
    }

    private final Map<String, Info> instances = new HashMap<>();

    InstanceTracker(String selfId) {
        this.selfId = selfId;
    }

    boolean updateInstance(String id, InetSocketAddress addr, long now) {
        if (selfId.equals(id)) {
            return false;
        }
        Info info = instances.get(id);
        if (info == null) {
            info = new Info();
            info.addr = addr;
            info.lastSeen = now;
            instances.put(id, info);
            return true;
        } else {
            boolean changedAddress = !info.addr.getAddress().equals(addr.getAddress());
            info.addr = addr;
            info.lastSeen = now;
            return changedAddress;
        }
    }

    boolean removeExpired(long now, long timeoutMs) {
        boolean changed = false;
        Iterator<Map.Entry<String, Info>> it = instances.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Info> e = it.next();
            if (now - e.getValue().lastSeen > timeoutMs) {
                it.remove();
                changed = true;
            }
        }
        return changed;
    }

    Set<String> currentAddresses() {
        LinkedHashSet<String> ips = new LinkedHashSet<>();
        for (Info info : instances.values()) {
            ips.add(info.addr.getAddress().getHostAddress());
        }
        return ips;
    }
}
