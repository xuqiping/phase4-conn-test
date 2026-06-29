package com.superprogrammer.workreport.service;

import com.superprogrammer.common.BusinessException;
import com.superprogrammer.common.ErrorCode;
import com.superprogrammer.workreport.dto.CreateInspirationNoteRequest;
import com.superprogrammer.workreport.dto.InspirationNoteDto;
import com.superprogrammer.workreport.entity.InspirationNote;
import com.superprogrammer.workreport.repository.InspirationNoteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class InspirationNoteService {

    private final InspirationNoteRepository inspirationNoteRepository;

    public InspirationNoteDto create(Long userId, CreateInspirationNoteRequest request) {
        InspirationNote note = new InspirationNote();
        note.setUserId(userId);
        note.setContent(request.content());
        note.setTags(normalizeTags(request.tags()));
        note.setSource(request.source() == null ? "DESKTOP" : request.source());
        note.setPlatformMessageId(request.platformMessageId());
        note.setReportConfigIds(request.reportConfigIds() == null ? List.of() : request.reportConfigIds());
        note.setCreatedBy(userId);
        note.setUpdatedBy(userId);
        InspirationNote saved = inspirationNoteRepository.insert(note);
        return toDto(saved);
    }

    public InspirationNoteDto createFromIm(Long userId, String content, List<String> tags, String platformMessageId) {
        InspirationNote note = new InspirationNote();
        note.setUserId(userId);
        note.setContent(content);
        note.setTags(normalizeTags(tags));
        note.setSource("IM");
        note.setPlatformMessageId(platformMessageId);
        note.setReportConfigIds(List.of());
        note.setCreatedBy(userId);
        note.setUpdatedBy(userId);
        InspirationNote saved = inspirationNoteRepository.insert(note);
        return toDto(saved);
    }

    public List<InspirationNoteDto> listByUser(Long userId, List<String> tags, LocalDate startDate, LocalDate endDate) {
        List<InspirationNote> notes;
        if (tags != null && !tags.isEmpty()) {
            notes = inspirationNoteRepository.findByUserIdAndTags(userId, tags);
        } else if (startDate != null && endDate != null) {
            notes = inspirationNoteRepository.findByUserIdAndDateRange(userId, startDate, endDate);
        } else {
            notes = inspirationNoteRepository.findByUserId(userId);
        }
        return notes.stream().map(this::toDto).collect(Collectors.toList());
    }

    @Transactional
    public InspirationNoteDto update(Long userId, Long id, CreateInspirationNoteRequest request) {
        InspirationNote note = requireOwnedByUser(id, userId);
        note.setContent(request.content());
        note.setTags(normalizeTags(request.tags()));
        note.setSource(request.source() == null ? note.getSource() : request.source());
        note.setPlatformMessageId(request.platformMessageId() == null ? note.getPlatformMessageId() : request.platformMessageId());
        note.setReportConfigIds(request.reportConfigIds() == null ? note.getReportConfigIds() : request.reportConfigIds());
        note.setUpdatedBy(userId);
        InspirationNote saved = inspirationNoteRepository.update(note);
        return toDto(saved);
    }

    @Transactional
    public InspirationNoteDto markReviewed(Long userId, Long id) {
        InspirationNote note = requireOwnedByUser(id, userId);
        note.setReviewedAt(OffsetDateTime.now(ZoneOffset.UTC));
        note.setUpdatedBy(userId);
        InspirationNote saved = inspirationNoteRepository.update(note);
        return toDto(saved);
    }

    @Transactional
    public void delete(Long userId, Long id) {
        requireOwnedByUser(id, userId);
        inspirationNoteRepository.softDeleteById(id, userId);
    }

    private InspirationNote requireOwnedByUser(Long id, Long userId) {
        InspirationNote note = inspirationNoteRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "灵感随记不存在"));
        if (!note.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问该灵感随记");
        }
        return note;
    }

    private List<String> normalizeTags(List<String> tags) {
        if (tags == null) {
            return List.of();
        }
        return tags.stream()
                .filter(t -> t != null && !t.isBlank())
                .map(String::trim)
                .distinct()
                .collect(Collectors.toList());
    }

    private InspirationNoteDto toDto(InspirationNote note) {
        return new InspirationNoteDto(
                note.getId(),
                note.getContent(),
                note.getTags(),
                note.getSource(),
                note.getPlatformMessageId(),
                note.getReportConfigIds(),
                note.getReviewedAt() == null ? null : note.getReviewedAt().toLocalDateTime(),
                note.getCreatedAt() == null ? null : note.getCreatedAt().toLocalDateTime(),
                note.getUpdatedAt() == null ? null : note.getUpdatedAt().toLocalDateTime()
        );
    }
}
