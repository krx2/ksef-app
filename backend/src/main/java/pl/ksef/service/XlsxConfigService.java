package pl.ksef.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.ksef.dto.XlsxConfigDto;
import pl.ksef.entity.XlsxConfiguration;
import pl.ksef.exception.ResourceNotFoundException;
import pl.ksef.repository.XlsxConfigurationRepository;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class XlsxConfigService {

    private final XlsxConfigurationRepository repository;

    public List<XlsxConfigDto.Response> listByUser(UUID userId) {
        return repository.findByUserIdOrderByCreatedAtDesc(userId)
                .stream().map(this::toResponse).toList();
    }

    public XlsxConfigDto.Response getById(UUID id, UUID userId) {
        return repository.findById(id)
                .filter(c -> c.getUserId().equals(userId))
                .map(this::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Config not found: " + id));
    }

    @Transactional
    public XlsxConfigDto.Response save(UUID userId, XlsxConfigDto.SaveRequest req) {
        XlsxConfiguration config = XlsxConfiguration.builder()
                .userId(userId)
                .name(req.getName())
                .description(req.getDescription())
                .fieldMappings(req.getFieldMappings())
                .build();
        return toResponse(repository.save(config));
    }

    @Transactional
    public XlsxConfigDto.Response update(UUID id, UUID userId, XlsxConfigDto.SaveRequest req) {
        XlsxConfiguration config = repository.findById(id)
                .filter(c -> c.getUserId().equals(userId))
                .orElseThrow(() -> new ResourceNotFoundException("Config not found: " + id));
        config.setName(req.getName());
        config.setDescription(req.getDescription());
        config.setFieldMappings(req.getFieldMappings());
        return toResponse(repository.save(config));
    }

    @Transactional
    public void delete(UUID id, UUID userId) {
        XlsxConfiguration config = repository.findById(id)
                .filter(c -> c.getUserId().equals(userId))
                .orElseThrow(() -> new ResourceNotFoundException("Config not found: " + id));
        repository.delete(config);
    }

    private XlsxConfigDto.Response toResponse(XlsxConfiguration c) {
        var r = new XlsxConfigDto.Response();
        r.setId(c.getId());
        r.setUserId(c.getUserId());
        r.setName(c.getName());
        r.setDescription(c.getDescription());
        r.setFieldMappings(c.getFieldMappings());
        r.setCreatedAt(c.getCreatedAt());
        r.setUpdatedAt(c.getUpdatedAt());
        return r;
    }
}
