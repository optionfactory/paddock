package net.optionfactory.paddock.spring.mvc;

import net.optionfactory.paddock.ParameterInfo.SendAs;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import net.optionfactory.paddock.ApiDoc;
import net.optionfactory.paddock.DataTypeInfo;
import net.optionfactory.paddock.EndpointInfo;
import net.optionfactory.paddock.EndpointsInfo;
import net.optionfactory.paddock.FieldInfo;
import net.optionfactory.paddock.ParameterInfo;
import org.apache.log4j.Logger;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.MethodParameter;
import org.springframework.core.ResolvableType;
import org.springframework.web.bind.annotation.MatrixVariable;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Introspects Spring controllers exposed in the application context for their
 * documentation. Enpoints and parameters are documented by adding the {@code @ApiDoc} annotation
 * to methods, parameters and fields.
 */
public class IntrospectingEndpointsFacade implements EndpointsFacade {

    private static final String NO_DOC = "undocumented";
    private static final String[] NO_HELP = new String[0];
    private static final Collector<DataTypeInfo, ?, TreeMap<String, DataTypeInfo>> INDEX_BY_TYPE = Collectors
            .groupingBy(
                    dataTypeInfo -> dataTypeInfo.type, 
                    () -> new TreeMap<String, DataTypeInfo>(),
                    Collectors.reducing(new DataTypeInfo(), (lhs, rhs) -> rhs)
            );

    private final ApiVersions apiVersions;
    private final Logger logger = Logger.getLogger(IntrospectingEndpointsFacade.class);

    public IntrospectingEndpointsFacade(String projectVersion, RequestMappingHandlerMapping mappings, String... apiVersions) {
        final Map<String, EndpointsInfo> versions = new HashMap<>();
        for (String apiVersion : apiVersions) {
            final List<Map.Entry<RequestMappingInfo, HandlerMethod>> apiInThisVersion = mappings
                    .getHandlerMethods()
                    .entrySet()
                    .stream()
                    .filter((Map.Entry<RequestMappingInfo, HandlerMethod> e) -> {
                        final HandlerMethod hm = e.getValue();
                        final RequestMapping classMapping = hm.getBeanType().getAnnotation(RequestMapping.class);
                        return classMapping != null && Stream.of(classMapping.value()).anyMatch(Predicate.isEqual(apiVersion));
                    }).collect(Collectors.toList());
            final List<EndpointInfo> endpoints = apiInThisVersion.stream()
                    .map(IntrospectingEndpointsFacade::mappingToEndpointInfo)
                    .sorted((l,r ) -> l.uris[0].compareTo(r.uris[0]))
                    .collect(Collectors.toList());
            final Map<String, DataTypeInfo> dataTypes = apiInThisVersion.stream()
                    .flatMap(IntrospectingEndpointsFacade::mappingToDataTypesInfo)
                    .collect(INDEX_BY_TYPE);
            versions.put(apiVersion, new EndpointsInfo(endpoints, dataTypes));
            logger.info(String.format("scanned for api version '%s' found %d endpoints referencing %d dataTypes", apiVersion, endpoints.size(), dataTypes.size()));
        }
        this.apiVersions = new ApiVersions(projectVersion, versions);
    }

    @Override
    public ApiVersions knownApi() {
        return apiVersions;
    }

    private static EndpointInfo mappingToEndpointInfo(Map.Entry<RequestMappingInfo, HandlerMethod> entry) {
        final RequestMappingInfo rmi = entry.getKey();
        final HandlerMethod handler = entry.getValue();
        ApiDoc methodDoc = handler.getMethodAnnotation(ApiDoc.class);
        final EndpointInfo info = new EndpointInfo();
        info.description = methodDoc != null ? methodDoc.value() : NO_DOC;
        info.help = methodDoc != null ? methodDoc.help() : NO_HELP;
        info.uris = rmi.getPatternsCondition().getPatterns().stream().toArray((int size) -> new String[size]);
        info.methods = rmi.getMethodsCondition().getMethods().stream().map(RequestMethod::toString).toArray((int size) -> new String[size]);
        info.parameters = Stream.of(handler.getMethodParameters())
                .filter(mp -> !ServletRequest.class.isAssignableFrom(mp.getParameterType()))
                .filter(mp -> !ServletResponse.class.isAssignableFrom(mp.getParameterType()))
                .map(IntrospectingEndpointsFacade::parameterInfo)
                .toArray((int size) -> new ParameterInfo[size]);
        info.response = shortenTypeName(ResolvableType.forMethodParameter(handler.getReturnType()).toString());
        return info;
    }

    private static ParameterInfo parameterInfo(MethodParameter mp) {
        mp.initParameterNameDiscovery(new DefaultParameterNameDiscoverer());
        final ParameterInfo pi = new ParameterInfo();
        pi.type = shortenTypeName(ResolvableType.forMethodParameter(mp).toString());
        if (mp.hasParameterAnnotation(PathVariable.class)) {
            pi.sendAs = SendAs.PathVariable;
        } else if (mp.hasParameterAnnotation(RequestBody.class)) {
            pi.sendAs = SendAs.RequestBody;
        } else if (mp.hasParameterAnnotation(RequestParam.class)) {
            pi.sendAs = SendAs.RequestParameter;
        } else if (mp.hasParameterAnnotation(RequestHeader.class)) {
            pi.sendAs = SendAs.RequestParameter;
        } else if (mp.hasParameterAnnotation(MatrixVariable.class)) {
            pi.sendAs = SendAs.MatrixVariable;
        } else {
            pi.sendAs = SendAs.Custom;
        }
        pi.name = mp.getParameterName();
        final ApiDoc parameterAnnotation = mp.getParameterAnnotation(ApiDoc.class);
        pi.description = parameterAnnotation != null ? parameterAnnotation.value() : mp.getParameterName();
        pi.help = parameterAnnotation != null ? parameterAnnotation.help() : NO_HELP;
        return pi;
    }

    private static Stream<DataTypeInfo> mappingToDataTypesInfo(Map.Entry<RequestMappingInfo, HandlerMethod> entry) {
        final HandlerMethod handler = entry.getValue();
        final Set<ResolvableType> alreadyInspected = new HashSet<>();
        final List<DataTypeInfo> result = new ArrayList<>();
        final Stream<MethodParameter> parameters = Stream.of(handler.getMethodParameters());
        final Stream<MethodParameter> returnType = Stream.of(handler.getReturnType());
        final Set<ResolvableType> discoveredTypes = Stream.concat(parameters, returnType)
                .map((MethodParameter mp) -> ResolvableType.forMethodParameter(mp))
                .filter((ResolvableType rt) -> typeShouldBeInspected(rt, alreadyInspected))
                .collect(Collectors.toSet());
        while (!discoveredTypes.isEmpty()) {
            final Set<ResolvableType> thisIteration = new HashSet<>(discoveredTypes);
            discoveredTypes.clear();
            for (ResolvableType discoveredType : thisIteration) {
                alreadyInspected.add(discoveredType);
                final DataTypeInfo dti = new DataTypeInfo();
                dti.type = shortenTypeName(discoveredType.toString());
                dti.fields = new TreeMap<>();
                dti.description = description(null, discoveredType, dti.type);
                dti.help = help(null, discoveredType, NO_HELP);
                if (isNotInPackage(discoveredType, "java") && isNotInPackage(discoveredType, "sun")) {
                    result.add(dti);
                }
                for (ResolvableType generic : discoveredType.getGenerics()) {
                    if (typeShouldBeInspected(generic, alreadyInspected)) {
                        discoveredTypes.add(generic);
                    }
                }
                final ResolvableType componentType = discoveredType.getComponentType();
                if (componentType != ResolvableType.NONE && typeShouldBeInspected(componentType, alreadyInspected)) {
                    discoveredTypes.add(componentType);
                }
                for (Field declaredField : discoveredType.getRawClass().getDeclaredFields()) {
                    if (Modifier.isStatic(declaredField.getModifiers())) {
                        continue;
                    }
                    final ResolvableType fieldType = ResolvableType.forField(declaredField, discoveredType);
                    final FieldInfo fi = new FieldInfo();
                    fi.type = shortenTypeName(fieldType.toString());
                    fi.description = description(declaredField, fieldType, fi.type);
                    fi.help = help(declaredField, fieldType, NO_HELP);
                    dti.fields.put(declaredField.getName(), fi);
                    if (typeShouldBeInspected(fieldType, alreadyInspected)) {
                        discoveredTypes.add(fieldType);
                    }
                }
            }
        }
        return result.stream();
    }

    private static String description(Field field, ResolvableType rt, String fallback) {
        if(field != null && field.getAnnotation(ApiDoc.class) != null){
            return field.getAnnotation(ApiDoc.class).value();
        }
        final Class<?> rawClass = rt.getRawClass();
        if (rawClass == null) {
            return fallback;
        }
        final ApiDoc apiDoc = rawClass.getAnnotation(ApiDoc.class);
        if (apiDoc == null) {
            return fallback;
        }
        return apiDoc.value();
    }

    private static String[] help(Field field, ResolvableType rt, String[] fallback) {
        if(field != null && field.getAnnotation(ApiDoc.class) != null){
            return field.getAnnotation(ApiDoc.class).help();
        }
        final Class<?> rawClass = rt.getRawClass();
        if (rawClass == null) {
            return fallback;
        }
        final ApiDoc apiDoc = rawClass.getAnnotation(ApiDoc.class);
        if (apiDoc == null) {
            return fallback;
        }
        return apiDoc.help();
    }

    private static boolean isNotInPackage(ResolvableType rt, String prefix) {
        return rt.getRawClass() != null && rt.getRawClass().getPackage() != null && !rt.getRawClass().getPackage().getName().startsWith(prefix);
    }

    private static boolean typeShouldBeInspected(final ResolvableType t, final Set<ResolvableType> knownTypes) {
        final Class<?> rawFieldType = t.getRawClass();
        if (rawFieldType == null) {
            return false;
        }
        if (rawFieldType.isPrimitive()) {
            return false;
        }
        if (rawFieldType.isArray()) {
            return typeShouldBeInspected(t.getComponentType(), knownTypes);
        }
        if (rawFieldType.getPackage() != null && rawFieldType.getPackage().getName().startsWith("java.lang")) {
            return false;
        }
        return !knownTypes.contains(t);
    }

    private static String shortenTypeName(String typeName) {
        return typeName.replaceAll("\\w+\\.", "").replaceAll("\\w+\\$", "");
    }

}
