package io.undertow.websockets;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * @author Stuart Douglas
 */
public class WebSocketExtension {

    private final String name;
    private final List<Parameter> parameters;

    public WebSocketExtension(String name, List<Parameter> parameters) {
        this.name = name;
        this.parameters = Collections.unmodifiableList(new ArrayList<Parameter>(parameters));
    }

    public String getName() {
        return name;
    }

    public List<Parameter> getParameters() {
        return parameters;
    }

    public static final class Parameter {
        private final String name;
        private final String value;

        public Parameter(String name, String value) {
            this.name = name;
            this.value = value;
        }

        public String getName() {
            return name;
        }

        public String getValue() {
            return value;
        }

        @Override
        public String toString() {
            return "{'" + name + '\'' +
                    ": '" + value + '\'' +
                    '}';
        }
    }

    @Override
    public String toString() {
        return "WebSocketExtension{" +
                "name='" + name + '\'' +
                ", parameters=" + parameters +
                '}';
    }

    public static List<WebSocketExtension> parse(final String extensionHeader) {
        List<WebSocketExtension> extensions = new ArrayList<WebSocketExtension>();
        //TODO: more efficient parsing algorithm
        String[] parts = extensionHeader.split(",");
        for (String part : parts) {
            String[] items = part.split(";");
            if (items.length > 0) {
                final List<Parameter> params = new ArrayList<Parameter>(items.length - 1);
                String name = items[0];
                for (int i = 1; i < items.length; ++i) {
                    String[] param = items[i].split("=");
                    if (param.length == 2) {
                        params.add(new Parameter(param[0], param[1]));
                    }
                }
                extensions.add(new WebSocketExtension(name, params));
            }
        }
        return extensions;
    }
}
