package com.bbororo.rtb.ssp.renderproof;

import com.bbororo.rtb.ssp.contract.SspMessages.ProofIssuance;
import com.bbororo.rtb.ssp.contract.SspMessages.RenderCompleted;
import com.bbororo.rtb.ssp.contract.SspMessages.RenderProof;
import com.bbororo.rtb.ssp.contract.SspMessages.VerifiedRender;
import java.util.Optional;

/** 렌더링 증표의 발급·무결성·기한 검증을 소유한다. */
public interface RenderProofService {

    RenderProof issue(ProofIssuance issuance);

    Optional<VerifiedRender> verify(RenderCompleted completed);
}
