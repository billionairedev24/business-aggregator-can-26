package ca.northline.merchants.application;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class MyBusinessesService implements ListMyBusinesses {

    private final BusinessDirectory directory;

    @Override
    public List<BusinessSummary> of(String userId) {
        return directory.businessesOf(userId);
    }
}
